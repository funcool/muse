(ns muse.core-spec
  #?(:clj
     (:require [clojure.test :refer (deftest is)]
               [promesa.core :as prom]
               [muse.core :as muse :refer (fmap flat-map)])
     :cljs
     (:require [cljs.test :refer-macros (deftest is async)]
               [promesa.core :as prom]
               [muse.core :as muse :refer (fmap flat-map)])))

(defrecord DList [size]
  muse/DataSource
  (fetch [_] (prom/resolved (range size)))
  muse/LabeledSource
  (resource-id [_] size))

(defrecord DListFail [size]
  muse/DataSource
  (fetch [_] (prom/rejected (ex-info "Invalid size" {:size size})))
  muse/LabeledSource
  (resource-id [_] size))

(defrecord Single [seed]
  muse/DataSource
  (fetch [_] (prom/resolved seed))
  muse/LabeledSource
  (resource-id [_] seed))

(defrecord Pair [seed]
  muse/DataSource
  (fetch [_] (prom/resolved [seed seed]))
  muse/LabeledSource
  (resource-id [_] seed))

(defn- mk-pair [seed] (Pair. seed))

(defn- sum-pair [[a b]] (+ a b))

(defn- id [v] (muse/value v))

(defn- assert-ast
  ([expected ast] (assert-ast expected ast nil))
  ([expected ast callback]
   #?(:clj
      (is (= expected (muse/run!! ast)))
      :cljs
      (async done (prom/then (muse/run! ast)
                             (fn [r]
                               (is (= expected r))
                               (when callback (callback))
                               (done)))))))

(defn- assert-err
  ([rx ast] (assert-err rx ast nil))
  ([rx ast callback]
   #?(:clj
      (try
        (muse/run!! ast)
      (catch Exception e
        (is (re-find rx (.getMessage e)))))
      :cljs
      (async done (prom/catch (muse/run! ast)
                              (fn [r]
                                (is (re-find rx (ex-message r)))
                                (when callback (callback))
                                (done)))))))

(deftest datasource-ast
  #?(:clj (is (= 10 (count (muse/run!! (DList. 10))))))
  #?(:clj (is (= 20 (count (muse/run!! (DList. 20))))))
  (assert-ast 30 (fmap count (DList. 30)))
  (assert-ast 40 (fmap inc (fmap count (DList. 39))))
  (assert-ast 50 (fmap count (fmap concat (DList. 30) (DList. 20))))
  (assert-ast 42 (flat-map id (Single. 42)))
  (assert-ast 42 (flat-map id (muse/value 42)))
  (assert-ast [15 15] (flat-map mk-pair (Single. 15)))
  (assert-ast [15 15] (flat-map mk-pair (muse/value 15)))
  (assert-ast 60 (fmap sum-pair (flat-map mk-pair (Single. 30))))
  (assert-ast 60 (fmap sum-pair (flat-map mk-pair (muse/value 30)))))

(deftest error-propagation
  (assert-err #"Invalid size"
              (fmap concat
                    (DList. 10)
                    (DListFail. 30)
                    (DList. 10))))

(deftest higher-level-api
  (assert-ast [0 1] (muse/collect [(Single. 0) (Single. 1)]))
  (assert-ast [] (muse/collect []))
  (assert-ast [[0 0] [1 1]] (muse/traverse mk-pair (DList. 2)))
  (assert-ast [] (muse/traverse mk-pair (DList. 0))))

(defn- recur-next [seed]
  (if (= 5 seed)
    (muse/value seed)
    (flat-map recur-next (Single. (inc seed)))))

(deftest recur-with-value
  (assert-ast 10 (muse/value 10))
  (assert-ast 5 (flat-map recur-next (Single. 0))))

(defn- assert-failed? [f]
  (is (thrown? #?(:clj AssertionError :cljs js/Error) (f))))

(deftest value-from-ast
  (assert-failed? #(muse/value (Single. 0)))
  (assert-failed? #(muse/value (fmap inc (muse/value 0)))))

;; attention! never do such mutations within "fetch" in real code
(defrecord Trackable [tracker seed]
  muse/DataSource
  (fetch [_] (prom/promise (fn [resolve reject]
                             (swap! tracker inc)
                             (resolve seed))))
  muse/LabeledSource
  (resource-id [_] seed))

(defrecord TrackableName [tracker seed]
  muse/DataSource
  (fetch [_] (prom/promise (fn [resolve reject]
                             (swap! tracker inc)
                             (resolve seed))))
  muse/LabeledSource
  (resource-id [_] [:name seed]))

(defrecord TrackableId [tracker id]
  muse/DataSource
  (fetch [_] (prom/promise (fn [resolve reject]
                             (swap! tracker inc)
                             (resolve id)))))

;; w explicit source labeling
#?(:clj
   (deftest caching-explicit-labels
     (let [t (atom 0)]
       (assert-ast 40
                   (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20)))
       (is (= 2 @t)))
     (let [t1 (atom 0)]
       (assert-ast 400
                   (fmap + (TrackableName. t1 100) (TrackableName. t1 100) (TrackableName. t1 200)))
       (is (= 2 @t1))))

   :cljs
   (deftest caching-explict-labels
     (let [t (atom 0)]
       (assert-ast 40
                   (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20))
                   (fn [] (is (= 2 @t)))))
     (let [t1 (atom 0)]
       (assert-ast 400
                   (fmap + (TrackableName. t1 100) (TrackableName. t1 100) (TrackableName. t1 200))
                   (fn [] (is (= 2 @t1)))))))

;; w/o explicit source labeling
#?(:clj
   (deftest caching-implicit-labels
     (let [t2 (atom 0)]
       (assert-ast 100 (fmap * (TrackableId. t2 10) (TrackableId. t2 10)))
       (is (= 1 @t2))))
   :cljs
   (deftest caching-implicit-labels
     (let [t2 (atom 0)]
       (assert-ast 100
                   (fmap * (TrackableId. t2 10) (TrackableId. t2 10))
                   (fn [] (is (= 1 @t2)))))))

;; different tree branches/levels
#?(:clj
   (deftest caching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140
                   (fmap +
                         (Trackable. t3 50)
                         (fmap (fn [[a b]] (+ a b))
                               (muse/collect [(Trackable. t3 40) (Trackable. t3 50)]))))
       (is (= 2 @t3)))
     (let [t4 (atom 0)]
       (assert-ast 1400
                   (fmap +
                         (TrackableName. t4 500)
                         (fmap (fn [[a b]] (+ a b))
                               (muse/collect [(TrackableName. t4 400) (TrackableName. t4 500)]))))
       (is (= 2 @t4))))
   :cljs
   (deftest caching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140
                   (fmap +
                         (Trackable. t3 50)
                         (fmap (fn [[a b]] (+ a b))
                               (muse/collect [(Trackable. t3 40) (Trackable. t3 50)])))
                   (fn [] (is (= 2 @t3)))))
     (let [t4 (atom 0)]
       (assert-ast 1400
                   (fmap +
                         (TrackableName. t4 500)
                         (fmap (fn [[a b]] (+ a b))
                               (muse/collect [(TrackableName. t4 400) (TrackableName. t4 500)])))
                   (fn [] (is (= 2 @t4)))))))

;; resouce should be identifiable: both Name and ID
(defrecord Country [iso-id]
  muse/DataSource
  (fetch [_] (prom/resolved {:regions [{:code 1} {:code 2} {:code 3}]})))

(defrecord Region [country-iso-id url-id]
  muse/DataSource
  (fetch [_] (prom/resolved (inc url-id))))

(deftest impossible-to-cache
  (assert-err #"Resource is not identifiable"
              (->> (Country. "es")
                   (muse/fmap :regions)
                   (muse/traverse #(Region. "es" (:code %))))))
