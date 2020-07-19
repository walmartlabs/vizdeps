(ns leiningen.vizdeps
  "Graphviz visualization of project dependencies."
  (:require
    [com.walmartlabs.vizdeps.common :as common
     :refer [gen-node-id]]
    [com.stuartsierra.dependency :as dep]
    [leiningen.core.main :as main]
    [dorothy.core :as d]
    [leiningen.core.classpath :as classpath]
    [leiningen.core.project :as project]))

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
    (-> (#'classpath/get-dependencies
          :dependencies nil
          (assoc project :dependencies [dependency]))
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
                  ;; get version from parent project if not found in this project
                  dep-version (or dep-version (second (get-in dependency-graph [:dependencies dep-name])))
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

(defn ^:private dependency-order
  "Returns the artifact names in dependency order."
  [artifacts]
  (let [tuples (for [artifact (vals artifacts)
                     dep (:deps artifact)]
                 [(:artifact-name artifact)
                  (:artifact-name dep)])
        graph (reduce (fn [g [artifact-name dependency-name]]
                        (dep/depend g artifact-name dependency-name))
                      (dep/graph)
                      tuples)]
    (dep/topo-sort graph)))

(defn ^:private prune-artifacts
  "Navigates the nodes to identify dependencies that include conflicts.
  Marks nodes that are referenced with conflicts, then marks any nodes that
  have a dependency to that node as well. The root node is always kept;
  other unmarked nodes are culled."
  [artifacts]
  (main/debug "Pruning artifacts")
  (let [order (dependency-order artifacts)
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
  (let [highlight-set (->> artifacts
                           keys
                           (filter (common/matches-any highlight-terms))
                           set)
        artifacts-highlighted (reduce (fn [m artifact-name]
                                        (assoc-in m [artifact-name :highlight?] true))
                                      artifacts
                                      highlight-set)
        ;; Now, find dependencies that target highlighted artifacts
        ;; and mark them as highlighted as well.
        add-highlight (fn [dep]
                        (if (-> dep :artifact-name highlight-set)
                          (assoc dep :highlight? true)
                          dep))]
    (reduce-kv (fn [artifacts-3 artifact-name artifact]
                 (assoc artifacts-3 artifact-name
                        (update artifact :deps
                                #(map add-highlight %))))
               {}
               artifacts-highlighted)))

(defn ^:private apply-focus
  "Identify a number of artifacts that match a focus term.  Only keep such artifacts, and those
  that transitively depend on them."
  [artifacts focus-terms]
  (let [focus-set (->> artifacts
                       keys
                       (filter (common/matches-any focus-terms))
                       set)
        keep-focus (fn [artifacts dep]
                     (-> artifacts
                         (get (:artifact-name dep))
                         :focused?))
        reducer (fn [m artifact-name]
                  (let [artifact (get artifacts artifact-name)
                        focus-deps (->> artifact
                                        :deps
                                        (filter #(keep-focus m %)))]
                    (if (or (focus-set artifact-name)
                            (seq focus-deps))
                      (assoc m artifact-name
                             (assoc artifact :focused? true
                                    :deps focus-deps))
                      m)))]
    (reduce reducer
            {}
            (dependency-order artifacts))))

(defn ^:private artifacts-map
  "Builds a map from artifact name (symbol) to an artifact record, with keys
  :artifact-name, :version, :node-id, :highlight?, :focus?, :conflict?, :root? and
  :deps.

  Each :dep has keys :artifact-name, :version, :conflict?, and :highlight?."
  [project options]
  (let [profiles (if-not (:dev options)
                   [:user]
                   [:user :dev])
        {:keys [:prune highlight focus]} options
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
          prune
          prune-artifacts

          (seq focus)
          (apply-focus focus)

          (seq highlight)
          (highlight-artifacts highlight)))))

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
                :penwidth 2
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
                :penwidth 2
                :weight 100)

         (:conflict? dep)
         (assoc :color :red
                :penwidth 2
                :weight 500
                :label (:version dep)))])))

(defn ^:private build-dot
  [project options]
  (-> (artifacts-map project options)
      (node-graph options)
      d/digraph
      d/dot))

(def ^:private cli-options
  [["-d" "--dev" "Include :dev dependencies in the graph."]
   ["-f" "--focus ARTIFACT" "Excludes artifacts whose names do not match a supplied value. Repeatable."
    :assoc-fn common/conj-option]
   ["-H" "--highlight ARTIFACT" "Highlight the artifact, and any dependencies to it, in blue. Repeatable."
    :assoc-fn common/conj-option]
   common/cli-no-view
   (common/cli-output-file "target/dependencies.pdf")
   ["-p" "--prune" "Exclude artifacts and dependencies that do not involve version conflicts."]
   common/cli-save-dot
   common/cli-vertical
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
