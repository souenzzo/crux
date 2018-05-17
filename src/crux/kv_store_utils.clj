(ns crux.kv-store-utils
  (:require [crux.kv-store :as ks]
            [crux.byte-utils :as bu])
  (:import clojure.lang.IReduceInit))

(defn seek [kvs k]
  (ks/iterate-with kvs
                   (fn [i]
                     (ks/-seek i k))))

(defn value [kvs seek-k]
  (when-let [[k v] (seek kvs seek-k)]
    (when (zero? (bu/compare-bytes seek-k k))
      v)))

(defn seek-first [kvs prefix-pred key-pred seek-k]
  (ks/iterate-with kvs
                   (fn [i]
                     (loop [[k v :as kv] (ks/-seek i seek-k)]
                       (when (and k (prefix-pred k))
                         (if (key-pred k)
                           kv
                           (recur (ks/-next i))))))))

(defn seek-and-iterate
  ([kvs key-pred seek-k]
   (seek-and-iterate kvs key-pred seek-k (partial into [])))
  ([kvs key-pred seek-k f]
   (ks/iterate-with kvs
                    (fn [i]
                      (f
                       (reify
                         IReduceInit
                         (reduce [this f init]
                           (loop [init init
                                  [k v :as kv] (ks/-seek i seek-k)]
                             (if (and k (key-pred k))
                               (let [result (f init kv)]
                                 (if (reduced? result)
                                   @result
                                   (recur result (ks/-next i))))
                               init)))))))))