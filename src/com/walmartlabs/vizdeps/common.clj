(ns com.walmartlabs.vizdeps.common
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [dorothy.core :as d]
    [clojure.java.browse :refer [browse-url]]
    [clojure.tools.cli :refer [parse-opts]])
  (:import (java.io File)))

(defn allowed-extension
  [path]
  (let [x (str/last-index-of path ".")
        ext (subs path (inc x))]
    (#{"png" "pdf"} ext)))

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

    (d/save! dot output-file {:format output-format})

    (when-not no-view
      (browse-url output-path)))

  nil)
