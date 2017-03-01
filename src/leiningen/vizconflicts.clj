(ns leiningen.vizconflicts
  "Graphviz visualization of conflicts in a multi-module project."
  (:require
    [com.walmartlabs.vizdeps.common :as common
     :refer [gen-graph-id]]
    [medley.core :refer [map-vals remove-vals]]
    [clojure.pprint :refer [pprint]]
    [leiningen.core.project :as project]
    [dorothy.core :as d]
    [leiningen.core.main :as main]))

(defn ^:private projects-map
  "Generates a map from sub module name (string)
   to initialized project."
  [root-project]
  (reduce (fn [m module-name]
            (main/info "Reading project" module-name)
            (let [project (-> (str module-name "/project.clj")
                              project/read
                              project/init-project)]
              (assoc m (:name project) project)))
          {}
          (:sub root-project)))

(defn ^:private artifact-versions-map
  [project->artifact->version-map]
  (reduce-kv (fn [m project-name artifact-versions]
               (reduce-kv (fn [m artifact-name artifact-version]
                            (update-in m [artifact-name artifact-version] conj project-name))
                          m
                          artifact-versions))
             {}
             project->artifact->version-map))

(defn ^:private no-conflicts?
  [version->project-map]
  (-> version->project-map count (< 2)))

(defn ^:private to-label [artifact-symbol version]
  (let [sym-ns (namespace artifact-symbol)]
    (apply str sym-ns
           (when sym-ns
             "/\n")
           (name artifact-symbol)
           "\n"
           version)))

(defn ^:private add-project-to-artifact-edges
  [graph artifact-symbol version->project-map]
  (reduce-kv (fn [g-1 version project-names]
               (let [artifact-node-id (gen-graph-id artifact-symbol)
                     artifact-node {:label (to-label artifact-symbol version)}]
                 (reduce (fn [g-2 project-name]
                           (let [project-node-id (gen-graph-id project-name)
                                 project-node {:label project-name
                                               :shape :doubleoctagon}
                                 edge [project-node-id artifact-node-id]]
                             (-> g-2
                                 (assoc-in [:nodes project-node-id] project-node)
                                 (update :edges conj edge))))
                         (assoc-in g-1 [:nodes artifact-node-id] artifact-node)
                         project-names)))
             graph
             version->project-map))


(defn ^:private node-graph
  [options artifact->versions-map]
  (let [base-graph {:nodes {}
                    :project-node-ids {}}]
    (reduce-kv (fn [statements artifact-symbol version->project-map]
                 (let [graph (add-project-to-artifact-edges base-graph artifact-symbol version->project-map)]
                   (conj statements
                         (d/subgraph (gen-graph-id :cluster)
                                     [(merge (common/graph-attrs options)
                                             {:label (str artifact-symbol)})
                                      (-> graph :nodes seq)
                                      (:edges graph)]))))
               [{:rankdir :LR}]
               artifact->versions-map)))


(def ^:private cli-options
  [(common/cli-output-file "target/conflicts.pdf")
   common/cli-save-dot
   common/cli-no-view
   common/cli-vertical
   common/cli-help])

(defn vizconflicts
  "Identify and visualize dependency conflicts across a multi-module project."
  {:pass-through-help true}
  [project & args]
  (when-let [options (common/parse-cli-options "vizconflicts" cli-options args)]
    (let [dot (->> project
                   projects-map
                   (map-vals common/flatten-dependencies)
                   ;; Reduce the inner maps to symbol -> version number string
                   (map-vals #(map-vals second %))
                   artifact-versions-map
                   (remove-vals no-conflicts?)
                   (node-graph options)
                   d/digraph
                   d/dot)]
      (common/write-files-and-view dot options)
      (main/info "Wrote conflicts chart to:" (:output-path options)))))
