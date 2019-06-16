(ns examples)

;; tag::start-system[]
(require '[crux.api :as crux])
(import (crux.api ICruxAPI))

(def ^crux.api.ICruxAPI system
  (crux/start-standalone-system {:kv-backend "crux.kv.memdb.MemKv"
                                 :event-log-dir "log/db-dir-1"
                                 :db-dir "data/db-dir-1"}))
;; end::start-system[]

;; tag::close-system[]
(.close system)
;; end::close-system[]

;; tag::start-cluster-node-system[]
(def ^crux.api.ICruxAPI system
  (crux/start-cluster-node {:kv-backend "crux.kv.memdb.MemKv"
                           :bootstrap-servers "localhost:29092"}))
;; end::start-cluster-node-system[]

;; tag::start-standalone-with-rocks[]
(def ^crux.api.ICruxAPI system
  (crux/start-standalone-system {:kv-backend "crux.kv.rocksdb.RocksKv"
                                :db-dir "data/db-dir-1"}))
;; end::start-standalone-with-rocks[]

;; tag::submit-tx[]
(crux/submit-tx
 system
 [[:crux.tx/put
   {:crux.db/id :dbpedia.resource/Pablo-Picasso ; id
    :name "Pablo"
    :last-name "Picasso"}
   #inst "2018-05-18T09:20:27.966-00:00"]]) ; valid time
;; end::submit-tx[]

;; tag::query[]
(crux/q (crux/db system)
       '{:find [e]
         :where [[e :name "Pablo"]]})
;; end::query[]

;; tag::query-entity[]
(crux/entity (crux/db system) :dbpedia.resource/Pablo-Picasso)
;; end::query-entity[]

;; tag::query-valid-time[]
(crux/q (crux/db system #inst "2018-05-19T09:20:27.966-00:00")
       '{:find [e]
         :where [[e :name "Pablo"]]})
;; end::query-valid-time[]

(comment
  ;; tag::should-get[]
  #{[:dbpedia.resource/Pablo-Picasso]}
  ;; end::should-get[]

  ;; tag::should-get-entity[]
  {:crux.db/id :dbpedia.resource/Pablo-Picasso
   :name "Pablo"
   :last-name "Picasso"}
  ;; end::should-get-entity[]
  )

;; tag::ek-example[]
(require '[crux.kafka.embedded :as ek])

(def storage-dir "dev-storage")
(def embedded-kafka-options
  {:crux.kafka.embedded/zookeeper-data-dir (str storage-dir "/zookeeper")
   :crux.kafka.embedded/kafka-log-dir (str storage-dir "/kafka-log")
   :crux.kafka.embedded/kafka-port 9092})

(def embedded-kafka (ek/start-embedded-kafka embedded-kafka-options))
;; end::ek-example[]

;; tag::ek-close[]
(.close embedded-kafka)
;; end::ek-close[]
