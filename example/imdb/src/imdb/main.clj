(ns imdb.main
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [crux.api :as api]))

(def list-columns?
  #{:name.basics/knownForTitles
    :name.basics/primaryProfession
    :title.crew/directors
    :title.crew/writers})

(def data-files
  {"name.basics" "data/name.basics.tsv"
   "title.principals" "data/title.principals.tsv"
   "title.akas" "data/title.akas.tsv"
   "title.crew" "data/title.crew.tsv"
   "title.episode" "data/title.episode.tsv"
   "title.ratings" "data/title.ratings.tsv"})

(defn ingest-data
  [crux]
  (log/info "starting to ingest data into crux")
  (let [futures
        (doall
          (for [[namespace file-path] data-files]
            (future
              (log/infof "starting to ingest: %s" file-path)
              (with-open [reader (io/reader file-path)]
                (let [submit-count (atom 0)
                      [header & rest]
                      (csv/read-csv reader :separator \tab)
                      header-keywords (mapv #(keyword namespace %) header)]
                  (doseq [rows (partition 100 rest)]
                    (swap! submit-count + 100)
                    (log/infof "submitting %s %s docs" file-path @submit-count)
                    (api/submit-tx
                      crux
                      (vec
                        (for [row rows]
                          (let [doc-id (java.util.UUID/randomUUID)
                                doc (zipmap header-keywords row)
                                doc (reduce
                                      (fn [doc key]
                                        (update doc key str/split #"\,"))
                                      doc
                                      (filter list-columns? (keys doc)))]
                            [:crux.tx/put (assoc doc :crux.db/id doc-id)])))))))
              (log/infof "completed %s" file-path))))]
    (doseq [f futures] @f)))

(def index-dir "data/db-dir")
(def log-dir "data/eventlog")

(def crux-options
  {:kv-backend "crux.kv.rocksdb.RocksKv"
   :bootstrap-servers "localhost:29092"
   :event-log-dir log-dir
   :db-dir index-dir
   :server-port 8080})

(defn run-system [{:keys [server-port] :as options} with-system-fn]
  (with-open [crux-system (api/start-cluster-node options)]
    (with-system-fn crux-system)))

(defn -main []
  (run-system
    crux-options
    (fn [crux-system]
      (def crux crux-system)
      ;;(ingest-data crux)
      (Thread/sleep Long/MAX_VALUE))))

(comment
  (def s (future
           (run-system
            crux-options
            (fn [crux-system]
              (def crux crux-system)
              (Thread/sleep Long/MAX_VALUE)))))
  (future-cancel s))


;;========================================================================
;; taken from https://hashrocket.com/blog/posts/using-datomic-as-a-graph-database

(defn paths
  "Returns a lazy seq of all non-looping path vectors starting with
  [<start-node>]"
  [nodes-fn path]
  (let [this-node (peek path)]
    (->> (nodes-fn this-node)
         (filter #(not-any? (fn [edge] (= edge [this-node %]))
                            (partition 2 1 path)))
         (mapcat #(paths nodes-fn (conj path %)))
         (cons path))))

(defn trace-paths [m start]
  (remove #(m (peek %)) (paths m [start])))

(defn- find-paths [from-map to-map matches]
  (for [n matches
        from (map reverse (trace-paths from-map n))
        to (map rest (trace-paths to-map n))]
    (vec (concat from to))))

(defn- neighbor-pairs [neighbors q coll]
  (for [node q
        nbr (neighbors node)
        :when (not (contains? coll nbr))]
    [nbr node]))

(defn bidirectional-bfs [start end neighbors]
  (let [find-pairs (partial neighbor-pairs neighbors)
        overlaps (fn [coll q] (seq (filter #(contains? coll %) q)))
        map-set-pairs (fn [map pairs]
                        (persistent! (reduce (fn [map [key val]]
                                  (assoc! map key (conj (get map key #{}) val)))
                                (transient map) pairs)))]
    (loop [preds {start nil} ; map of outgoing nodes to where they came from
           succs {end nil}   ; map of incoming nodes to where they came from
           q1 (list start)   ; queue of outgoing things to check
           q2 (list end)]    ; queue of incoming things to check
      (when (and (seq q1) (seq q2))
        (if (<= (count q1) (count q2))
          (let [pairs (find-pairs q1 preds)
                preds (map-set-pairs preds pairs)
                q1 (map first pairs)]
            (if-let [all (overlaps succs q1)]
              (find-paths preds succs (set all))
              (recur preds succs q1 q2)))
          (let [pairs (find-pairs q2 succs)
                succs (map-set-pairs succs pairs)
                q2 (map first pairs)]
            (if-let [all (overlaps preds q2)]
              (find-paths preds succs (set all))
              (recur preds succs q1 q2))))))))


;; =============================================================================

(defn connected-movies
  [db snapshot person-id]
  (map first
       (api/q db snapshot
              {:find '[movie-id]
               :where '[[?p :title.principals/nconst person-id]
                        [?p :title.principals/tconst movie-id]]
               :args [{:person-id person-id}]})))

(defn connected-people
  [db snapshot movie-id]
  (map first
       (api/q db snapshot
              {:find '[person-id]
               :where '[[?p :title.principals/tconst movie-id]
                        [?p :title.principals/nconst person-id]]
               :args [{:movie-id movie-id}]})))

(defn connected-to
  [db snapshot id]
  (concat
    (connected-movies db snapshot id)
    (connected-people db snapshot id)))

(defn find-id-paths [db snapshot source target]
  (bidirectional-bfs source target (partial connected-to db snapshot)))

(defn ids->docs
  [db snapshot ids]
  (vec
    (for [id ids]
      (or
        (first
          (api/q db snapshot
                 {:find '[id title]
                  :where '[[?p :title.akas/titleId id]
                           [?p :title.akas/title title]]
                  :args [{:id id}]}))
        (first
          (api/q db snapshot
                 {:find '[id title]
                  :where '[[?p :name.basics/nconst id]
                           [?p :name.basics/primaryName title]]
                  :args [{:id id}]}))
        id))))

(comment


  (clojure.pprint/pprint
    (let [db (api/db crux)]
      (with-open [snapshot (api/new-snapshot db)]
        (let [paths (find-id-paths db snapshot "nm0000001" "nm0000006")]
          (doall (map (partial ids->docs db snapshot) paths))))))


  )
