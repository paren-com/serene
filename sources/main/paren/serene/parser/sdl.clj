(ns paren.serene.parser.sdl
  (:require
   [clojure.spec.alpha :as s]
   [com.walmartlabs.lacinia.parser.schema :as lacinia.parser.schema]
   [paren.serene.parser :as parser]
   [paren.serene.parser.lacinia]))

(defn ^:private sdl-schema->lacinia-schema
  [sdl-str]
  (-> sdl-str
    (lacinia.parser.schema/parse-schema {})
    (update :scalars (fn [scalars]
                       (->> scalars
                         (map (fn [[k v]]
                                [k (assoc v
                                     :parse (s/conformer any?)
                                     :serialize (s/conformer any?))]))
                         (into {}))))))

(defmethod parser/parse :sdl [_ sdl cfg]
  (parser/parse :lacinia (sdl-schema->lacinia-schema sdl) cfg))

