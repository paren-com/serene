(ns paren.serene.dev
  (:require
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
   [paren.serene :as serene]
   [paren.serene.compiler :as compiler]
   [paren.serene.parser :as parser]
   [paren.serene.parser.lacinia]
   [paren.serene.parser.sdl]
   [paren.serene.schema :as schema]))

(defn ^:private pprint-spit
  [f form]
  (io/make-parents f)
  (binding [*print-length* nil]
    (->> form
      pprint
      with-out-str
      (spit f))))

#_
(pprint-spit "target/tmp.clj"
  (macroexpand-1
    '(serene/defschema schema :sdl "resources/test/paren/serene/schema.graphql"
       :alias (fn [kw]
                (keyword
                  (namespace kw)
                  (str (name kw) "-alias")))
       :namespace :gql)))
