(ns leiningen.vizdeps
  "Graphviz visualization of project dependencies."
  (:require [leiningen.core.main :as main]
            [clojure.tools.cli :refer [parse-opts]]
            [dorothy.core :as d]
            [clojure.java.browse :refer [browse-url]]
            [leiningen.core.classpath :as classpath]
            [leiningen.core.project :as project]
            [clojure.string :as str]))

(defn- dependency->label
  [dependency]
  (let [[artifact-name version] dependency]
    (format "%s%n%s" artifact-name version)))

(defn gen-graph-id
  [k]
  (str (gensym (str (name k) "-"))))

(defn- find-dependency-graph-id
  [graph dependency]
  (get-in graph [:deps (first dependency)]))

(defn- add-edge
  [graph from-graph-id to-graph-id]
  (update-in graph [:edges] conj [from-graph-id to-graph-id]))

(defn- add-node
  [graph node-id attributes]
  (assoc-in graph [:nodes node-id] (if (string? attributes)
                                     {:label attributes}
                                     attributes)))

(defn- add-dependencies
  [graph containing-node-id hierarchy]
  (reduce-kv (fn [graph dependency sub-hierarchy]
               (let [node-id (find-dependency-graph-id graph dependency)]
                 (if node-id
                   (add-edge graph containing-node-id node-id)
                   (let [sub-node-id (-> dependency first gen-graph-id)]
                     (-> graph
                         (add-node sub-node-id (dependency->label dependency))
                         (add-edge containing-node-id sub-node-id)
                         (add-dependencies sub-node-id sub-hierarchy))))))
             graph
             hierarchy))


(defn- dependency-graph
  "Builds out a structured dependency graph, from which a Dorothy node graph can be constructed."
  [project include-dev]
  (let [profiles (if-not include-dev [:user] [:user :dev])
        project' (project/set-profiles project profiles)
        root-dependency [(symbol (-> project :group str) (-> project :name str)) (:version project)]]

    ;; :nodes - map from node graph id to node attributes
    ;; :edges - list of edge tuples [from graph id, to graph id]
    ;; :sets - map from set id (a keyword or a symbol, typically) to a generated node graph id (a symbol)
    ;; :deps - map from artifact symbol (e.g., com.walmartlab/shared-deps) to a generated node graph id
    (-> {:nodes {:root {:label (dependency->label root-dependency)
                        :shape :doubleoctagon}}
         :edges []
         :sets {}
         :deps {}}
        (add-dependencies :root (classpath/dependency-hierarchy :dependencies project')))))

(defn- node-graph
  [dependency-graph options]
  (reduce into
          [(d/graph-attrs {:rankdir (if (:vertical options) :TD :LR)})]
          [(for [[k v] (:nodes dependency-graph)]
             [k v])
           (:edges dependency-graph)]))

(defn- build-dot
  [project options]
  (-> (dependency-graph project (:dev options))
      (node-graph options)
      d/digraph
      d/dot))

(defn- allowed-extension
  [path]
  (let [x (str/last-index-of path ".")
        ext (subs path (inc x))]
    (#{"png" "pdf"} ext)))

(def ^:private cli-options
  [["-o" "--output-file FILE" "Output file path. Extension chooses format: pdf or png."
    :default "target/dependencies.pdf"
    :validate [allowed-extension "Supported output formats are 'pdf' and 'png'."]]
   ["-n" "--no-view" "If given, the image will not be opened after creation."
    :default false]
   ["-v" "--vertical" "Use a vertical, not horizontal, layout."]
   ["-d" "--dev" "Include :dev dependencies in the graph."]
   ["-h" "--help" "This usage summary."]])

(defn- usage [summary errors]
  (let [main (->> ["Usage: lein vizdeps [options]"
                   ""
                   "Options:"
                   summary]
                  (str/join \newline))]
    (println main)

    (when errors
      (println "\nErrors:")
      (doseq [e errors] (println " " e)))))

(defn vizdeps
  "Visualizes dependencies using Graphviz.

  Normally, this will generate an image and raise a frame to view it.
  Command line options allow the image to be written to a file instead."
  {:pass-through-help true}
  [project & args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]

    (if (or (:help options) errors)
      (usage summary errors)
      (let [{:keys [output-file no-view dev]} options
            output-format (-> output-file allowed-extension keyword)]
        (-> project
            (build-dot options)
            (d/save! output-file {:format output-format}))

        (main/info "Wrote dependency chart to:" output-file)

        (when-not no-view
          (browse-url output-file))))))
