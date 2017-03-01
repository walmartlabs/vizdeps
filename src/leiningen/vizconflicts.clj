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
            (assoc m module-name
                   (-> (str "/Users/hlship/workspaces/sample/multiproject/" module-name "/project.clj")
                       project/read
                       project/init-project)))
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

(defn ^:private project-name->node [graph project-name]
  (if-let [node-id (get-in graph [:project-node-ids project-name])]
    [graph node-id]
    (let [new-node-id (gen-graph-id project-name)
          new-node {:label project-name
                    :shape :doubleoctagon}]
      [(-> graph
           (assoc-in [:project-node-ids project-name] new-node-id)
           (assoc-in [:nodes new-node-id] new-node))
       new-node-id])))

(defn ^:private add-version-edge
  [graph project-node-id artifact-node-id version]
  (let [edge [project-node-id artifact-node-id {:label version}]]
    (update graph :edges conj edge)))

(defn ^:private to-label [sym]
  (let [sym-ns (namespace sym)]
    (apply str sym-ns
           (when sym-ns
             "/\n")
           (name sym))))

(defn ^:private add-project-to-artifact-edges
  [graph artifact-symbol version->project-map]
  (let [artifact-node-id (gen-graph-id artifact-symbol)
        artifact-node {:label (to-label artifact-symbol)}]
    (reduce-kv (fn [g version project-names]
                 (reduce (fn [g project-name]
                           (let [[g' node-id] (project-name->node g project-name)]
                             (add-version-edge g' node-id artifact-node-id version)))
                         g
                         project-names))
               (assoc-in graph [:nodes artifact-node-id] artifact-node)
               version->project-map)))

(defn ^:private conflicts-graph
  [options artifact->versions-map]
  (let [graph (-> {:nodes {}
                   :project-node-ids {}
                   :edges []})]
    (reduce-kv add-project-to-artifact-edges
               graph
               artifact->versions-map)))

(defn ^:private node-graph
  [options conflicts-graph]
  (concat
    [(d/graph-attrs {:rankdir :LR})]
    (-> conflicts-graph :nodes seq)
    (:edges conflicts-graph)))


(def ^:private cli-options
  [(common/cli-output-file "target/conflicts.pdf")
   common/cli-save-dot
   common/cli-no-view
   common/cli-help])

(defn vizconflicts
  "Identify an visualize dependency conflicts across a multi-module project."
  {:pass-through-help true}
  [project & args]
  (when-let [options (common/parse-cli-options "vizconflicts" cli-options args)]
    (let [dissoc-org-clojure (fn [m]
                               (dissoc m 'org.clojure/clojure))
          dot (->> project
                   projects-map
                   (map-vals common/flatten-dependencies)
                   ;; Reduce the inner maps to symbol -> version number string
                   (map-vals #(map-vals second %))
                   artifact-versions-map
                   dissoc-org-clojure
                   (remove-vals no-conflicts?)
                   (conflicts-graph options)
                   (node-graph options)
                   d/digraph
                   d/dot)]
      (common/write-files-and-view dot options)
      (main/info "Wrote conflicts chart to:" (:output-path options)))))

(defn v [& args]
  (let [project (-> "/Users/hlship/workspaces/sample/multiproject/project.clj"
                    project/read
                    project/init-project)]
    (apply vizconflicts project args)))

