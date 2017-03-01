(ns leiningen.vizdeps
  "Graphviz visualization of project dependencies."
  (:require
    [com.walmartlabs.vizdeps.common :as common
     :refer [gen-graph-id]]
    [leiningen.core.main :as main]
    [dorothy.core :as d]
    [leiningen.core.classpath :as classpath]
    [leiningen.core.project :as project]))

(defn ^:private dependency->label
  [dependency]
  (let [[artifact-name version] dependency
        ^String group (some-> artifact-name namespace name)
        ^String module (name artifact-name)]
    (str group
         (when group "/\n")
         module
         \newline
         version)))


(defn ^:private add-edge
  [graph from-graph-id to-graph-id resolved-dependency version]
  (let [version-mismatch? (not= version (second resolved-dependency))
        highlight? (= (:highlight graph) (first resolved-dependency))
        edge-attrs (cond-> nil
                     highlight? (assoc :color :blue)
                     version-mismatch? (assoc :color :red :label version))
        edge (cond-> [from-graph-id to-graph-id]
               edge-attrs (conj edge-attrs))]
    (update graph :edges conj edge)))

(defn ^:private add-node
  [graph artifact node-id attributes]
  (let [highlight? (= (:highlight graph) artifact)
        node-attrs (cond-> (if (string? attributes)
                             {:label attributes}
                             attributes)
                     highlight? (assoc :color :blue))]
    (assoc-in graph [:nodes node-id] node-attrs)))

(defn ^:private normalize-artifact
  [dependency]
  (let [artifact (first dependency)]
    (if-not (= (namespace artifact) (name artifact))
      dependency
      (assoc dependency 0 (symbol (name artifact))))))

(defn ^:private immediate-dependencies
  [project dependency]
  (if (some? dependency)
    (try
      (-> (#'classpath/get-dependencies-memoized
            :dependencies nil
            (assoc project :dependencies [dependency])
            nil)
          (get dependency)
          ;; Tracking dependencies on Clojure itself overwhelms the graph
          (as-> $
                (remove #(= 'org.clojure/clojure (first %))
                        $))
          vec)
      (catch Exception e
        (throw (ex-info (format "Exception resolving dependencies of %s: %s"
                                (pr-str dependency)
                                (.getMessage e)
                                )
                        {:dependency dependency}
                        e))))
    []))

(declare ^:private add-dependencies)

(defn ^:private add-dependency-tree
  [graph project containing-node-id dependency]
  (let [[artifact version] dependency
        resolved-dependency (get-in graph [:dependencies artifact])
        node-id (get-in graph [:node-ids artifact])]
    ;; When the node has been found from some other dependency,
    ;; just add a new edge to it.
    (if node-id
      (add-edge graph containing-node-id node-id resolved-dependency version)
      ;; Otherwise its a new dependency in the graph and we want
      ;; to add the corresponding node and the edge to it,
      ;; but also take care of dependencies of the new node.
      (let [sub-node-id (-> dependency first gen-graph-id)]
        (try
          (-> graph
              (assoc-in [:node-ids (first dependency)] sub-node-id)
              (add-node artifact sub-node-id (dependency->label dependency))
              (add-edge containing-node-id sub-node-id resolved-dependency version)
              ;; Aether/Pomenegrate may reach a dependency by a differnt navigation of the
              ;; dependency tree, and so have a different version than the one for this
              ;; dependency, so always use the A/P resolved dependency (including version and
              ;; exclusions) to compute transitive dependencies.
              (add-dependencies sub-node-id project
                                (immediate-dependencies project resolved-dependency)))
          (catch Exception e
            (throw (ex-info (str "Exception processing dependencies of "
                                 (pr-str resolved-dependency) ": "
                                 (.getMessage e))
                            {:dependency resolved-dependency}
                            e))))))))

(defn ^:private add-dependencies
  [graph containing-node-id project dependencies]
  (reduce (fn [g coord]
            (add-dependency-tree g project containing-node-id coord))
          graph
          dependencies))


(defn ^:private dependency-graph
  "Builds out a structured dependency graph, from which a Dorothy node graph can be constructed."
  [project include-dev highlight-artifact]
  (let [profiles (if-not include-dev [:user] [:user :dev])
        project' (project/set-profiles project profiles)
        root-dependency [(symbol (-> project :group str) (-> project :name str)) (:version project)]
        dependency-map (common/flatten-dependencies project')]
    ;; :nodes - map from node graph id to node attributes
    ;; :edges - list of edge tuples [from graph id, to graph id]
    ;; :sets - map from set id (a keyword or a symbol, typically) to a generated node graph id (a symbol)
    ;; :node-ids - map from artifact symbol (e.g., com.walmartlab/shared-deps) to a generated node graph id
    ;; :dependencies - map from artifact symbol to version number, as supplied by Leiningen
    (add-dependencies {:nodes {:root {:label (dependency->label root-dependency)
                                      :shape :doubleoctagon}}
                       :edges []
                       :sets {}
                       :node-ids {}
                       :highlight (when highlight-artifact
                                    (symbol highlight-artifact))
                       :dependencies dependency-map}
                      :root
                      project'
                      (->> project'
                           :dependencies
                           (map normalize-artifact)))))

(defn ^:private node-graph
  [dependency-graph options]
  (concat
    [(d/graph-attrs (common/graph-attrs options))]
    (-> dependency-graph :nodes seq)
    (:edges dependency-graph)))

(defn ^:private build-dot
  [project options]
  (-> (dependency-graph project (:dev options) (:highlight options))
      (node-graph options)
      d/digraph
      d/dot))

(def ^:private cli-options
  [(common/cli-output-file "target/dependencies.pdf")
   common/cli-save-dot
   common/cli-no-view
   common/cli-highlight
   common/cli-vertical
   ["-d" "--dev" "Include :dev dependencies in the graph."]
   common/cli-help])

(defn vizdeps
  "Visualizes dependencies using Graphviz.

  Normally, this will generate an image and raise a frame to view it.
  Command line options allow the image to be written to a file instead."
  {:pass-through-help true}
  [project & args]
  (when-let [options (common/parse-cli-options "vizdeps" cli-options args)]
    (let [dot (build-dot project options)]
      (common/write-files-and-view dot options)
      (main/info "Wrote dependency chart to:" (:output-path options)))))
