(ns muse.core
  #?(:cljs (:require-macros [muse.core :refer (run!)]))
  (:require [promesa.core :as prom]
            [clojure.string :as s]
            [cats.core :as m]
            [cats.context :as ctx :include-macros true]
            [cats.protocols :as proto])
  (:refer-clojure :exclude (run!)))


(declare fmap)
(declare flat-map)
(declare value)

(def ast-monad
  (reify
    proto/Context
    (-get-level [_] ctx/+level-default+)

    proto/Functor
    (-fmap [_ f mv] (fmap f mv))

    proto/Monad
    (-mreturn [_ v] (value v))
    (-mbind [_ mv f] (flat-map f mv))))

(defprotocol DataSource
  "Defines fetch method for the concrete data source. Relies on a promise
   as a result value for fetch call (to return immediately to the caller
   and perform fetch operations asynchronously). If defrecord is used
   to define data source, name of record class will be used to batch fetches
   from the same round of execution as well as to cache previous results
   and sent fetch requests. Use LabeledSource protocol when using reify to
   build data source instance.

   See example here: https://github.com/funcool/muse/blob/master/docs/sql.md"
  (fetch [this]))

(defprotocol LabeledSource
  "The id of DataSource instance, used in cache, tracing and stat.
   If not specified library will use (:id data-source) as an identifier.
   In order to redefine resource-name you should return seq of 2 elements:
   '(name id) as a result of resource-id call"
  (resource-id [this]))

(defprotocol BatchedSource
  "Group few data fetches into a single request to remote source (i.e.
   Redis MGET or SQL SELECT .. IN ..). Return promise and write to it
   map from ID to generic fetch response (as it was made without batching).

   See example here: https://github.com/funcool/muse/blob/master/docs/sql.md"
  (fetch-multi [this resources]))

(defprotocol MuseAST
  (childs [this])
  (inject [this env])
  (done? [this]))

(defprotocol ComposedAST
  (compose-ast [this f]))

(defrecord MuseDone [value]
  proto/Contextual
  (-get-context [_] ast-monad)

  ComposedAST
  (compose-ast [_ f2] (MuseDone. (f2 value)))

  MuseAST
  (childs [_] nil)
  (done? [_] true)
  (inject [this _] this))

(defn- resource-name [v]
  (pr-str (type v)))

(defn cache-id
  [res]
  (let [id (if (satisfies? LabeledSource res)
             (resource-id res)
             (:id res))]
    (assert (not (nil? id))
            (str "Resource is not identifiable: " res
                 " Please, use LabeledSource protocol or record with :id key"))
    id))

(def cache-path (juxt resource-name cache-id))

(defn cached-or [env res]
  (let [cache (get env :cache)
        cached (get-in cache (cache-path res) ::not-found)]
    (if (= ::not-found cached)
      res
      (MuseDone. cached))))

(defn inject-into [env node]
  (if (satisfies? DataSource node)
    (cached-or env node)
    (inject node env)))

(defn print-node
  [node]
  (if (satisfies? DataSource node)
    (str (resource-name node) "[" (cache-id node) "]")
    (with-out-str (print node))))

(defn print-childs
  [nodes]
  (s/join " " (map print-node nodes)))

(deftype MuseMap [f values]
  proto/Contextual
  (-get-context [_] ast-monad)

  ComposedAST
  (compose-ast [_ f2] (MuseMap. (comp f2 f) values))

  MuseAST
  (childs [_] values)
  (done? [_] false)
  (inject [_ env]
    (let [next (map (partial inject-into env) values)]
      (if (= (count next) (count (filter done? next)))
        (MuseDone. (apply f (map :value next)))
        (MuseMap. f next))))

  Object
  (toString [_] (str "(" f " " (print-childs values) ")")))

(defn- ast?
  [ast]
  (or (satisfies? MuseAST ast)
      (satisfies? DataSource ast)))

(defn assert-ast!
  [ast]
  (assert (ast? ast)))

(deftype MuseFlatMap [f values]
  proto/Contextual
  (-get-context [_] ast-monad)

  MuseAST
  (childs [_] values)
  (done? [_] false)
  (inject [_ env]
    (let [next (map (partial inject-into env) values)]
      (if (every? done? next)
        (let [result (apply f (map :value next))]
          ;; xxx: refactor to avoid dummy leaves creation
          (if (satisfies? DataSource result) (MuseMap. identity [result]) result))
        (MuseFlatMap. f next))))

  Object
  (toString [_] (str "(" f " " (print-childs values) ")")))

(deftype MuseValue [value]
  proto/Contextual
  (-get-context [_] ast-monad)

  ComposedAST
  (compose-ast [_ f2] (MuseMap. f2 [value]))

  MuseAST
  (childs [_] [value])
  (done? [_] false)
  (inject [_ env]
    (let [next (inject-into env value)]
      (if (done? next) (MuseDone. (:value next)) next)))

  Object
  (toString [_] (print-node value)))

;; High-level API

(defn value
  [v]
  (assert (not (ast? v))
          (str "The value is already an AST: " v))
  (MuseDone. v))

(defn fmap
  [f muse & muses]
  (if (and (not (seq muses))
           (satisfies? ComposedAST muse))
    (compose-ast muse f)
    (MuseMap. f (cons muse muses))))

;; xxx: make it compatible with algo.generic
(defn flat-map
  [f muse & muses]
  (MuseFlatMap. f (cons muse muses)))

;; xxx: use macro instead for auto-partial function building
(def <$> fmap)
(defn >>= [muse f] (flat-map f muse))

(defn collect
  [muses]
  (if (seq muses)
    (apply (partial fmap vector) muses)
    (value [])))

(defn traverse
  [f muses]
  (flat-map #(collect (map f %)) muses))

(defn next-level
  [ast-node]
  (if (satisfies? DataSource ast-node)
    (list ast-node)
    (when-let [values (childs ast-node)]
      (mapcat next-level values))))

;; fetching

(defn dedupe-sources
  [sources]
  (->> sources
       (group-by cache-id)
       vals
       (map first)))

(defn fetch-many-caching
  [sources]
  (let [all-sources (dedupe-sources sources)
        ids (map cache-id all-sources)
        responses (map fetch all-sources)]
    (prom/then (prom/all responses) #(zipmap ids %))))

(defn fetch-one-caching
  [source]
  (prom/then (fetch source)
             (fn [res]
               {(cache-id source) res})))

(defn fetch-sources
  [[head & tail :as sources]]
  (if-not (seq tail)
    (fetch-one-caching head)
    (if (satisfies? BatchedSource head)
      (fetch-multi head tail)
      (fetch-many-caching sources))))

(defn fetch-resource
  [[resource-name sources]]
  (prom/then (fetch-sources sources)
             (fn [resp]
               [resource-name resp])))

;; AST interpreter

(defn interpret-ast*
 [ast-node cache success! error!]
 (let [requests (next-level ast-node)]
   (if-not (seq requests)
     (if (done? ast-node)
       (success! (:value ast-node))
       (let [next-ast (inject-into {:cache cache} ast-node)]
         (recur next-ast cache success! error!)))
     (let [requests-by-type (group-by resource-name requests)
           responses (map fetch-resource requests-by-type)]
       (ctx/with-context prom/promise-context
         (prom/branch (prom/all responses)
                      (fn [results]
                        (let [next-cache (into cache results)
                              next-ast (inject-into {:cache next-cache} ast-node)]
                          (interpret-ast* next-ast next-cache success! error!)))
                      error!))))))

(defn interpret-ast
  ([ast]
   (interpret-ast ast {}))
  ([ast cache]
   (prom/promise
      (fn [resolve reject]
        (interpret-ast* ast cache resolve reject)))))

;; Macros

#?(:clj
   (defmacro run!
     "Asynchronously executes the body, returning immediately to the
      calling thread. Rebuild body AST in order to:
      * fetch data sources async (when possible)
      * cache result of previously made fetches
      * batch calls to the same data source (when applicable)
      Returns a promise which will receive the result of
      the body when completed."
     [ast]
     `(ctx/with-context ast-monad (interpret-ast ~ast))))

#?(:clj
   (defmacro run!!
     "dereferences the the promise returned by (run! ast).
      Will block if nothing is available. Not available on
      ClojureScript."
     [ast]
     `(deref (run! ~ast))))
