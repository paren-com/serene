(ns paren.serene.compiler
  (:refer-clojure
   :exclude [compile])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.schema :as schema]
   #?(:cljs [cljs.reader :refer [read-string]]))
  #?(:cljs (:require-macros
            [paren.serene.compiler])))

(def ^:dynamic *schema* nil)

(s/def ::spec (s/keys
                :req [::name ::form ::schema-key ::schema-path]))

(s/def ::name qualified-keyword?)

(s/def ::form (s/nonconforming
                (s/or
                  :keyword qualified-keyword?
                  :seq (s/and seq? ::serializable)
                  :set (s/and set? ::serializable)
                  :symbol symbol?)))

(s/def ::schema-key keyword?)

(s/def ::schema-path (s/coll-of
                       (s/nonconforming
                         (s/or
                           :keyword simple-keyword?
                           :integer integer?))
                       :kind vector?))

(s/def ::serializable (fn serializable?
                        [form]
                        (binding [*print-length* nil
                                  *print-level* nil]
                          (try
                            (= (-> form pr-str read-string) form)
                            (catch #?(:clj Throwable :cljs :default) e
                              false)))))

(defn ^:private conform
  "Like `s/conform` except throws an exception if invalid."
  [spec x]
  (let [conformed (s/conform spec x)]
    (if (s/invalid? conformed)
      (throw (ex-info
               (str "Invalid `" spec "`: " (s/explain-str spec x))
               {:spec spec
                :invalid x}))
      conformed)))

(defn ^:private strs->kw
  [strs]
  (keyword
    (some->> strs butlast (str/join "."))
    (last strs)))

(defn ^:private schema-spec
  [schema-path form]
  (let [key-segs (->> schema-path
                   count
                   inc
                   range
                   (map (fn [i]
                          (->> i
                            (subvec schema-path 0)
                            (get-in *schema*)
                            :name)))
                   (remove nil?)
                   (map name))
        name-segs (-> *ns*
                    ns-name
                    name
                    (cons key-segs))]
    {::name (strs->kw name-segs)
     ::form form
     ::schema-key (strs->kw key-segs)
     ::schema-path schema-path}))

(defn ^:private schema-type-ref-spec-name
  [obj]
  (keyword
    (-> *ns* ns-name name)
    (-> obj :name name)))

(defn ^:private schema-type-ref-spec-form
  [type-ref]
  (->> type-ref
    (walk/postwalk (fn [x]
                     (if (map? x)
                       (let [{:keys [kind name ofType]} x]
                         (if (= kind :NON_NULL)
                           (assoc ofType :non-null? true)
                           x))
                       x)))
    ((fn type-ref->spec-form
       [{:as obj
         :keys [kind non-null? ofType]}]
       (let [spec (if (= kind :LIST)
                    `(s/coll-of ~(type-ref->spec-form ofType)
                       :kind sequential?)
                    (schema-type-ref-spec-name obj))]
         (if-not non-null?
           `(s/nilable ~spec)
           spec))))))

(defn ^:private schema-possible-types-spec-form
  [types]
  (->> types
    (mapcat
      (juxt :name schema-type-ref-spec-name))
    (cons `s/or)
    (list `s/nonconforming)))

(defn ^:private schema-input-values-spec-form
  [input-values]
  (->> input-values
    (reduce
      (fn [m {:keys [defaultValue type]
              ::keys [spec]}]
        (let [opt? (or
                     defaultValue
                     (not= (:kind type) :NON_NULL))
              k (if opt? :opt-un :req-un)]
          (update m k conj (::name spec))))
      {:opt-un []
       :req-un []})
    (apply concat)
    (cons `s/keys)))

(defmulti ^:private -attach-specs
  (fn [schema-path {:keys [__typename kind]}]
    (case __typename
      :__EnumValue :ENUM_VALUE
      :__Field :FIELD
      :__InputValue :INPUT_VALUE
      :__Schema :SCHEMA
      :__Type kind)))

(defn ^:private attach-specs
  ([obj]
   (attach-specs [] obj))
  ([schema-path obj]
   (-attach-specs schema-path obj)))

(defn ^:private attach-child-specs
  [schema-path obj k]
  (->> obj
    k
    (map-indexed (fn [i obj]
                   (attach-specs (conj schema-path k i) obj)))
    vec
    (assoc obj k)))

(defmethod -attach-specs :ENUM [schema-path obj]
  (let [obj (attach-child-specs schema-path obj :enumValues)]
    (->> obj
      :enumValues
      (mapcat (comp (juxt ::schema-key ::name) ::spec))
      (cons `s/or)
      (list `s/nonconforming)
      (schema-spec schema-path)
      (assoc obj ::spec))))

(defmethod -attach-specs :ENUM_VALUE [schema-path obj]
  (->> obj
    :name
    name
    hash-set
    (schema-spec schema-path)
    (assoc obj ::spec)))

(defn ^:private args-map-kw
  [kw]
  (keyword
    (str (namespace kw) "." (name kw))
    "&args"))

(defmethod -attach-specs :FIELD [schema-path obj]
  (let [obj (attach-child-specs schema-path obj :args)
        spec (schema-spec
               schema-path
               (if (= (:name obj) :__typename)
                 (->> schema-path
                   (drop-last 2)
                   (get-in *schema*)
                   :name
                   name
                   hash-set)
                 (-> obj :type schema-type-ref-spec-form)))]
    (assoc obj
      ::spec spec
      ::args-spec (-> spec
                    (update ::name args-map-kw)
                    (assoc ::form (-> obj :args schema-input-values-spec-form))
                    (update ::schema-key args-map-kw)
                    (update ::schema-path conj :args)))))

(defmethod -attach-specs :INPUT_OBJECT [schema-path obj]
  (let [obj (attach-child-specs schema-path obj :inputFields)]
    (->> obj
      :inputFields
      schema-input-values-spec-form
      (schema-spec schema-path)
      (assoc obj ::spec))))

(defmethod -attach-specs :INPUT_VALUE [schema-path obj]
  (->> obj
    :type
    schema-type-ref-spec-form
    (schema-spec schema-path)
    (assoc obj ::spec)))

(defmethod -attach-specs :INTERFACE [schema-path obj]
  (let [obj (attach-child-specs schema-path obj :fields)]
    (->> obj
      :possibleTypes
      schema-possible-types-spec-form
      (schema-spec schema-path)
      (assoc obj ::spec))))

(defmethod -attach-specs :OBJECT [schema-path obj]
  (let [obj (attach-child-specs schema-path obj :fields)]
    (->> obj
      :fields
      (mapv (comp ::name ::spec))
      (list :opt-un)
      (cons `s/keys)
      (schema-spec schema-path)
      (assoc obj ::spec))))

(defmethod -attach-specs :SCALAR [schema-path obj]
  (->> (case (:name obj)
         :Boolean `boolean?
         :Float `float?
         :ID `string?
         :Int `integer?
         :String `string?
         `any?)
    (schema-spec schema-path)
    (assoc obj ::spec)))

(defmethod -attach-specs :SCHEMA [schema-path obj]
  (binding [*schema* obj]
    (attach-child-specs schema-path obj :types)))

(defmethod -attach-specs :UNION [schema-path obj]
  (->> obj
    :possibleTypes
    schema-possible-types-spec-form
    (schema-spec schema-path)
    (assoc obj ::spec)))

(defn ^:private topo-sort-specs
  [spec-map]
  (let [sort-aliases (fn sort-aliases [sorted unsorted]
                       (let [no-deps (->> unsorted
                                       (remove (fn [[k v]]
                                                 (contains? unsorted (::form v))))
                                       (into (sorted-map)))
                             sorted' (concat sorted no-deps)
                             unsorted' (apply dissoc unsorted (keys no-deps))]
                         (if (seq unsorted')
                           (sort-aliases sorted' unsorted')
                           sorted')))
        aliases (->> spec-map
                  (filter #(-> % val ::form keyword?))
                  (into {}))]
    (map val
      (concat
        (apply dissoc spec-map (keys aliases))
        (sort-aliases [] aliases)))))

(defmulti transducer
  "Takes a transducer name and arg.
  Returns a transducer that will modify specs during compilation."
  (fn transducer-dispatch
    [k v]
    k))

(defn ^:private comp-transducers
  [xforms]
  (cond
    (coll? xforms) (->> xforms
                     (map (partial apply transducer))
                     (apply comp (map identity)))
    (nil? xforms) (map identity)
    (fn? xforms) xforms
    :else (-> "Cannot be coerced into a transducer."
            (ex-info xforms)
            throw)))

(defn compile
  ([schema]
   (compile schema nil))
  ([schema xforms]
   (binding [*schema* (attach-specs (conform ::schema/schema schema))]
     (->> *schema*
       (tree-seq coll? seq)
       (transduce
         (comp
           (mapcat (juxt ::spec ::args-spec))
           (remove nil?)
           (comp-transducers xforms)
           (map (partial conform ::spec))
           (map (juxt ::name identity)))
         (completing
           (fn [m [k v]]
             (when (contains? m k)
               (->
                 (str "Duplicate spec name: " k)
                 (ex-info {:spec v})
                 throw))
             (assoc! m k v))
           persistent!)
         (transient {}))
       topo-sort-specs
       (mapv
         (fn [{::keys [name form]}]
           `(s/def ~name ~form)))))))
