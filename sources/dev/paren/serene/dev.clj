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
   [org.httpkit.client :as http]
   [paren.serene :as serene]
   [paren.serene.introspection :as introspection]))

(defn ^:private pprint-spit
  [f form]
  (io/make-parents f)
  (binding [*print-length* nil]
    (->> form
      pprint
      with-out-str
      (spit f))))
