(ns paren.serene.dev
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as sg]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [com.walmartlabs.lacinia :as lacinia]
   [com.walmartlabs.lacinia.parser.schema :as lacinia.parser.schema]
   [com.walmartlabs.lacinia.schema :as lacinia.schema]
   [com.walmartlabs.lacinia.util :as lacinia.util]
   [expound.alpha :refer [expound]]
   [fipp.clojure :as fipp]
   [org.httpkit.client :as http]
   [paren.serene :as serene]
   [paren.serene.compiler :as compiler]
   [paren.serene.schema :as schema]))

(defn pprint-spit
  [f form]
  (io/make-parents f)
  (with-open [w (io/writer f)]
    (binding [*out* w]
      (fipp/pprint
        form
        {:print-length nil
         :print-level nil
         :print-meta true
         :width 100}))))

(defmacro def-github-specs []
  `(serene/def-specs
     (schema/fetch
       "https://api.github.com/graphql"
       {:headers {"Authorization" (str "bearer " (System/getenv "GITHUB_ACCESS_TOKEN"))}})
     {:gen-object-fields 3
      :prefix :gh}))
