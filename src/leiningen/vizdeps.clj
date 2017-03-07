(ns leiningen.vizdeps
  "Graphviz visualization of project dependencies."
  (:require
    [com.walmartlabs.vizdeps.common :as common
     :refer [gen-node-id]]
    [com.stuartsierra.dependency :as dep]
    [leiningen.core.main :as main]
    [dorothy.core :as d]
    [leiningen.core.classpath :as classpath]
    [leiningen.core.project :as project]
    [clojure.string :as str]))

(defn ^:private artifact->label
  [artifact]
  {:pre [artifact]}
  (let [{:keys [artifact-name version]} artifact
        ^String group (some-> artifact-name namespace name)
        ^String module (name artifact-name)]
    (str group
         (when group "/\n")
         module
         \newline
         version)))

(defn ^:private normalize-artifact
  [dependency]
  (let [artifact (first dependency)]
    (if-not (= (namespace artifact) (name artifact))
      dependency
      (assoc dependency 0 (symbol (name artifact))))))

(defn ^:private immediate-dependencies
  [project dependency]
  (if (some? dependency)
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
    []))

(declare ^:private add-dependency-tree)

(defn ^:private add-dependency-node
  [dependency-graph artifact-name artifact-version dependencies]
  (reduce (fn [g dep]
            (let [[dep-name dep-version] dep
                  g-1 (add-dependency-tree g dep-name dep-version)]
              (if-let [dep-artifact (get-in g-1 [:artifacts dep-name])]
                (let [dep-map {:artifact-name dep-name
                               :version dep-version
                               :conflict? (not= dep-version
                                                (:version dep-artifact))}]
                  (update-in g-1 [:artifacts artifact-name :deps] conj dep-map))
                ;; If the artifact is excluded, the dependency graph will
                ;; not contain the artifact.
                g-1)))
          ;; Start with a new node for the artifact
          (assoc-in dependency-graph
                    [:artifacts artifact-name]
                    {:artifact-name artifact-name
                     :version artifact-version
                     :node-id (gen-node-id artifact-name)
                     :deps []})
          dependencies))

(defn ^:private add-dependency-tree
  [dependency-graph artifact-name artifact-version]
  (let [[_ resolved-version :as resolved-dependency] (get-in dependency-graph [:dependencies artifact-name])
        ;; When using managed dependencies, the version (from :dependencies) may be nil,
        ;; so subtitute the version from the resolved dependency in that case.
        version (or artifact-version resolved-version)
        artifact (get-in dependency-graph [:artifacts artifact-name])]
    (main/debug (format "Processing %s %s"
                        (str artifact-name) version))

    (cond

      (nil? resolved-dependency)
      (do
        (main/debug "Skipping excluded artifact")
        dependency-graph)

      ;; Has the artifact already been added?
      (some? artifact)
      dependency-graph

      :else
      (add-dependency-node dependency-graph
                           artifact-name
                           resolved-version
                           ;; Find the dependencies of the resolved (not requested) artifact
                           ;; and version. Recursively add those artifacts to the graph
                           ;; and set up dependencies.
                           (immediate-dependencies (:project dependency-graph)
                                                   resolved-dependency)))))

(defn ^:private prune-artifacts
  "Navigates the nodes to identify dependencies that include conflicts.
  Marks nodes that are referenced with conflicts, then marks any nodes that
  have a dependency to that node as well. The root node is always kept;
  other unmarked nodes are culled."
  [artifacts]
  (main/debug "Pruning artifacts")
  (let [tuples (for [artifact (vals artifacts)
                     dep (:deps artifact)]
                 [(:artifact-name artifact)
                  (:artifact-name dep)])
        graph (reduce (fn [g [artifact-name dependency-name]]
                        (dep/depend g artifact-name dependency-name))
                      (dep/graph)
                      tuples)
        order (dep/topo-sort graph)
        mark-graph (fn [artifacts artifact-name]
                     (assoc-in artifacts [artifact-name :conflict?] true))
        get-transitives (fn [artifacts artifact]
                          (->> artifact
                               :deps
                               (filter #(->> %
                                             :artifact-name
                                             artifacts
                                             ;; May be nil here, when an earlier processed artifact
                                             ;; was culled.  Otherwise, check if the :conflict? flag
                                             ;; was set on the artifact.
                                             :conflict?))))
        marked-graph (reduce (fn [artifacts-1 artifact-name]
                               (->> (artifacts-1 artifact-name)
                                    :deps
                                    (filter :conflict?)
                                    (map :artifact-name)
                                    (reduce mark-graph artifacts-1)))
                             artifacts
                             order)]
    (reduce (fn [artifacts-1 artifact-name]
              (let [artifact (artifacts-1 artifact-name)
                    ;; Get transitive dependencies to conflict artifacts (dropping
                    ;; dependencies to non-conflict artifacts, if any).
                    transitives (get-transitives artifacts-1 artifact)
                    keep? (or (:conflict? artifact)
                              (:root? artifact)
                              (seq transitives))]
                (if keep?
                  (assoc artifacts-1 artifact-name
                         (assoc artifact
                                :conflict? true
                                :deps transitives))
                  ;; Otherwise we don't need this artifact at all
                  (dissoc artifacts-1 artifact-name))))
            marked-graph
            order)))

(defn ^:private highlight-artifacts
  [artifacts highlight-terms]
  (let [highlight? (fn [artifact-name]
                     (some #(str/includes? (name artifact-name) %)
                           highlight-terms))
        artifacts-highlighted (reduce (fn [m artifact-name]
                                        (assoc-in m [artifact-name :highlight?] true))
                                      artifacts
                                      (->> artifacts
                                           keys
                                           (filter highlight?)))
        ;; Now, find dependencies that target highlighted artifacts
        ;; and mark them as highlighted as well.
        add-highlight (fn [dep]
                        (if (get-in artifacts-highlighted [(:artifact-name dep) :highlight?])
                          (assoc dep :highlight? true)
                          dep))]
    (reduce-kv (fn [artifacts-3 artifact-name artifact]
                 (assoc artifacts-3 artifact-name
                        (update artifact :deps
                                #(map add-highlight %))))
               {}
               artifacts-highlighted)))

(defn ^:private artifacts-map
  "Builds a map from artifact name (symbol) to an artifact record, with keys
  :artifact-name, :version, :node-id, :highlight?, :conflict?, :root? and
  :deps.

  Each :dep has keys :artifact-name, :version, :conflict?, and :highlight?."
  [project options]
  (let [profiles (if-not (:dev options)
                   [:user]
                   [:user :dev])
        project' (project/set-profiles project profiles)
        root-artifact-name (symbol (-> project :group str) (-> project :name str))
        root-dependency [root-artifact-name (:version project)]
        dependency-map (common/flatten-dependencies project')
        root-dependencies (->> project'
                               :dependencies
                               (map normalize-artifact))]
    (-> (add-dependency-node {:artifacts {}
                              :project project
                              :dependencies dependency-map}
                             root-artifact-name
                             (:version project)
                             root-dependencies)
        ;; Just need the artifacts from here out
        :artifacts
        ;; Ensure the root artifact is drawn properly and never pruned
        (assoc-in [root-artifact-name :root?] true)
        (cond->
          (:prune options)
          prune-artifacts

          (-> options :highlight seq)
          (highlight-artifacts (:highlight options))))))

(defn ^:private node-graph
  [artifacts options]
  (concat
    [(d/graph-attrs (common/graph-attrs options))]
    ;; All nodes:
    (for [artifact (vals artifacts)]
      [(:node-id artifact)
       (cond-> {:label (artifact->label artifact)}
         (:root? artifact)
         (assoc :shape :doubleoctagon)

         (:highlight? artifact)
         (assoc :color :blue
                :fontcolor :blue))])

    ;; Now, all edges:
    (for [artifact (vals artifacts)
          :let [node-id (:node-id artifact)]
          dep (:deps artifact)]
      [node-id
       (get-in artifacts [(:artifact-name dep) :node-id])
       (cond-> {}
         (:highlight? dep)
         (assoc :color :blue
                :weight 100)

         (:conflict? dep)
         (assoc :color :red
                :weight 500
                :label (:version dep)))])))

(defn ^:private build-dot
  [project options]
  (-> (artifacts-map project options)
      (node-graph options)
      d/digraph
      d/dot))

(def ^:private cli-options
  [(common/cli-output-file "target/dependencies.pdf")
   common/cli-save-dot
   common/cli-no-view
   ["-H" "--highlight ARTIFACT" "Highlight the artifact, and any dependencies to it, in blue."
    :assoc-fn common/conj-option]
   common/cli-vertical
   ["-d" "--dev" "Include :dev dependencies in the graph."]
   ["-p" "--prune" "Exclude artifacts and dependencies that do not involve version conflicts."]
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
