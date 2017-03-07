(ns com.walmartlabs.vizdeps.common
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [dorothy.core :as d]
    [clojure.java.browse :refer [browse-url]]
    [clojure.tools.cli :refer [parse-opts]]
    [leiningen.core.classpath :as classpath]
    [leiningen.core.main :as main])
  (:import (java.io File)))

(defn gen-node-id
  "Create a unique string, based on the provided node-name (keyword, symbol, or string)."
  [node-name]
  (str (gensym (str (name node-name) "-"))))

(defn ^:private allowed-extension
  [path]
  (let [x (str/last-index-of path ".")
        ext (subs path (inc x))]
    (#{"png" "pdf"} ext)))

(defn graph-attrs
  [options]
  {:rankdir (if (:vertical options) :TD :LR)})

(def cli-help ["-h" "--help" "This usage summary."])

(def cli-save-dot ["-s" "--save-dot" "Save the generated GraphViz DOT file well as the output file."])

(def cli-no-view
  ["-n" "--no-view" "If given, the image will not be opened after creation."
   :default false])

(defn cli-output-file
  [default-path]
  ["-o" "--output-file FILE" "Output file path. Extension chooses format: pdf or png."
   :id :output-path
   :default default-path
   :validate [allowed-extension "Supported output formats are 'pdf' and 'png'."]])

(def cli-vertical
  ["-v" "--vertical" "Use a vertical, not horizontal, layout."])

(defn conj-option
  "Used as :assoc-fn for an option to conj'es the values together."
  [m k v]
  (update m k conj v))

(defn ^:private usage
  [command summary errors]
  (->> [(str "Usage: lein " command " [options]")
        ""
        "Options:"
        summary]
       (str/join \newline)
       println)

  (when errors
    (println "\nErrors:")
    (doseq [e errors] (println " " e)))

  nil)

(defn parse-cli-options
  "Parses the CLI options; handles --help and errors (returning nil) or just
  returns the parsed options."
  [command cli-options args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (if (or (:help options) errors)
      (usage command summary errors)
      options)))

(defn write-files-and-view
  "Given a Graphviz document string (dot) and CLI options, write the file(s) and, optionally,
  open the output file."
  [dot options]
  (let [{:keys [output-path no-view]} options
        ^File output-file (io/file output-path)
        output-format (-> output-path allowed-extension keyword)
        output-dir (.getParentFile output-file)]

    (when output-dir
      (.mkdirs output-dir))

    (when (:save-dot options)
      (let [x (str/last-index-of output-path ".")
            dot-path (str (subs output-path 0 x) ".dot")
            ^File dot-file (io/file dot-path)]
        (spit dot-file dot)))

    (main/debug "Create output diagram")

    (d/save! dot output-file {:format output-format})

    (when-not no-view
      (browse-url output-path)))

  nil)

(defn ^:private build-dependency-map
  "Consumes a hierarchy and produces a map from artifact to version, used to identify
  which dependency linkages have had their version changed."
  ([hierarchy]
   (build-dependency-map {} hierarchy))
  ([version-map hierarchy]
   (reduce-kv (fn [m dep sub-hierarchy]
                (-> m
                    (assoc (first dep) dep)
                    (build-dependency-map sub-hierarchy)))
              version-map
              hierarchy)))

(defn flatten-dependencies
  [project]
  "Resolves dependencies for the project and returns a map from artifact
  symbol to artifact coord vector."
  (-> (classpath/managed-dependency-hierarchy :dependencies :managed-dependencies
                                              project)
      build-dependency-map))

