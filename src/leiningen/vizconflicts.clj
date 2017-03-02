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
  "Generates a map from sub module name (a symbol) to initialized project."
  [root-project]
  (reduce (fn [m module-name]
            (main/info "Reading project" module-name)
            (let [project (-> (str module-name "/project.clj")
                              project/read
                              project/init-project)
                  {project-name :name
                   project-group :group} project
                  artifact-symbol (symbol project-group project-name)]
              (assoc m artifact-symbol project)))
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

(defn ^:private to-label
  ([artifact-symbol]
   (to-label artifact-symbol nil))
  ([artifact-symbol version]
   (let [sym-ns (namespace artifact-symbol)]
     (apply str sym-ns
            (when sym-ns
              "/\n")
            (name artifact-symbol)
            (when version "\n")
            version))))

(defn ^:private direct-dependency?
  [projects project-name artifact-symbol]
  (some (fn [dep]
          (= artifact-symbol (first dep)))
        (-> projects (get project-name) :dependencies)))

(defn ^:private add-project-node
  "Returns a tuple of graph and node id."
  [graph project-name]
  (if-let [node-id (get-in graph [:project-node-ids project-name])]
    [graph node-id]
    (let [new-node-id (gen-graph-id project-name)
          project-node {:label (to-label project-name)
                        :shape :doubleoctagon}
          graph' (-> graph
                     (assoc-in [:project-node-ids project-name] new-node-id)
                     (assoc-in [:nodes new-node-id] project-node))]
      [graph' new-node-id])))

(defn ^:private add-project-to-artifact-edges
  [graph artifact-symbol version->project-map projects]
  (reduce-kv (fn [g-1 version project-names]
               (let [artifact-node-id (gen-graph-id artifact-symbol)
                     artifact-node {:label (to-label artifact-symbol version)}]
                 (reduce (fn [g-2 project-name]
                           (let [[graph' project-node-id] (add-project-node g-2 project-name)
                                 direct? (direct-dependency? projects project-name artifact-symbol)
                                 edge (cond-> [project-node-id artifact-node-id]
                                        (not direct?) (conj {:style :dotted}))]
                             (update graph' :edges conj edge)))
                         (assoc-in g-1 [:nodes artifact-node-id] artifact-node)
                         project-names)))
             graph
             version->project-map))

(defn ^:private add-project-to-project-edges
  [graph version->project-map projects]
  (let [{:keys [project-node-ids]} graph
        involved-projects (into #{}
                                (apply concat (vals version->project-map)))
        project-dependencies (reduce (fn [m project-name]
                                       (assoc m project-name
                                              (into #{}
                                                    (->> (get-in projects [project-name :dependencies])
                                                         (map first)
                                                         (keep involved-projects)))))
                                     {}
                                     involved-projects)
        edges (for [[from-project-name dependencies] project-dependencies
                    :let [from-node-id (get project-node-ids from-project-name)]
                    to-project-name dependencies]
                [from-node-id (get project-node-ids to-project-name)])]
    (update graph :edges concat edges)))

(defn ^:private node-graph
  [options projects artifact->versions-map]
  (let [base-graph {:nodes {}
                    :project-node-ids {}}]
    (reduce-kv (fn [statements artifact-symbol version->project-map]
                 (let [graph (-> base-graph
                                 (add-project-to-artifact-edges artifact-symbol version->project-map projects)
                                 (add-project-to-project-edges version->project-map projects))]
                   (conj statements
                         (d/subgraph (gen-graph-id (str "cluster_" (name artifact-symbol)))
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
    (let [projects (projects-map project)
          dot (->> projects
                   (map-vals common/flatten-dependencies)
                   ;; Reduce the inner maps to symbol -> version number string
                   (map-vals #(map-vals second %))
                   artifact-versions-map
                   (remove-vals no-conflicts?)
                   (node-graph options projects)
                   d/digraph
                   d/dot)]
      (common/write-files-and-view dot options)
      (main/info "Wrote conflicts chart to:" (:output-path options)))))
