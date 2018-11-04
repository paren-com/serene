(ns paren.serene-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.test.check]
   [clojure.walk :as walk]
   [paren.serene :as serene]
   #?@(:clj [[clojure.data.json :as json]
             [clojure.java.io :as io]
             [com.walmartlabs.lacinia :as lacinia]
             [com.walmartlabs.lacinia.parser.schema :as lacinia.parser.schema]
             [com.walmartlabs.lacinia.schema :as lacinia.schema]]
       :cljs [[doo.runner :include-macros true]]))
  #?(:cljs (:require-macros
            [paren.serene-test :refer [test-spec test-specs]])))

(s/check-asserts true)

#?(:clj (defn ^:private get-introspection-query-response []
          (let [sdl (-> "paren/serene/schema.graphql" io/resource slurp)
                edn (lacinia.parser.schema/parse-schema sdl {})
                edn (update edn :scalars (fn [scalars]
                                           (->> scalars
                                             (map (fn [[k v]]
                                                    [k (assoc v
                                                         :parse (s/conformer any?)
                                                         :serialize (s/conformer any?))]))
                                             (into {}))))
                schema (lacinia.schema/compile edn)
                resp (lacinia/execute schema serene/introspection-query {} {})]
            resp)))

(defn ^:private email? [x]
  (and
    (string? x)
    (str/includes? x "@")))

(s/def ::email email?)

(s/def ::username (complement email?))

(defn ^:private map-of-email-or-username?
  "Is it a map with either `:email` or `:username` but not both?"
  [{:as m
    :keys [email username]}]
  (and
    (map? m)
    (or email username)
    (not (and email username))))

(s/def ::map-of-email-or-username map-of-email-or-username?)

(defn ^:private iff-has-child-then-child?
  "Is it a recursive map that has `:child` iff `:hasChild` is `true`?"
  [{:as m
    :keys [hasChild child]}]
  (and
    (map? m)
    (or
      (and
        (true? hasChild)
        (iff-has-child-then-child? child))
      (and
        (false? hasChild)
        (not (iff-has-child-then-child? child))))))

(s/def ::iff-has-child-then-child iff-has-child-then-child?)

(defn ^:private alias-spec [kw]
  (keyword
    (let [ns (namespace kw)]
      (cond
        (nil? ns) "alias.gql"
        (str/starts-with? ns "gql") (str "alias." ns)
        :else (str "alias.gql." ns)))
    (name kw)))

(def ^:private defined-specs
  (serene/def-specs (get-introspection-query-response)
    (comp
      (serene/prefix (constantly :gql))
      (serene/postfix-args (constantly :-args))
      (serene/extend {:gql.Query/randPosInt            `pos-int?
                      :gql/InputObject_EmailOrUsername ::map-of-email-or-username
                      :gql/Interface_EmailOrUsername   ::map-of-email-or-username
                      :gql/Object_EmailOrUsername      ::map-of-email-or-username
                      :gql/Object_IffHasChildThenChild ::iff-has-child-then-child
                      :gql/Scalar_Email                ::email})
      (serene/alias alias-spec))))

(defmacro ^:private test-spec [spec {:keys [valid invalid]}]
  `(do
     ~@(for [example valid]
         `(t/is (s/valid? ~spec ~example)))
     ~@(for [example invalid]
         `(t/is (not (s/valid? ~spec ~example))))))

(defmacro ^:private test-specs [specs examples]
  `(do
     ~@(for [spec specs]
         `(test-spec ~spec ~examples))))

(t/deftest defschema-test
  (t/testing "specs and aliases are defined"
    (doseq [spec [;; scalars
                  :gql/Boolean
                  :gql/Float
                  :gql/ID
                  :gql/Int
                  :gql/String
                  :gql/Scalar_Email
                  ;; enums
                  :gql/Enum_DefaultScalar
                  ;; enum values
                  :gql.Enum_DefaultScalar/Boolean
                  ;; input objects
                  :gql/InputObject_EmailOrUsername
                  ;; input object fields
                  :gql.InputObject_EmailOrUsername/email
                  ;; interfaces
                  :gql/Interface_EmailOrUsername
                  ;; interface fields
                  :gql.Interface_EmailOrUsername/email
                  ;; interface field args
                  :gql.Interface_EmailOrUsername/username-args

                  :gql.Interface_EmailOrUsername.username/downcase
                  ;; objects
                  :gql/Query
                  :gql/Mutation
                  :gql/Subscription
                  :gql/Object_EmailOrUsername
                  ;; object fields
                  :gql.Query/__typename
                  :gql.Object_EmailOrUsername/__typename
                  :gql.Object_EmailOrUsername/email
                  ;; object field args
                  :gql.Object_EmailOrUsername/username-args
                  :gql.Object_EmailOrUsername.username/downcase
                  ;; unions
                  :gql/Union_ID]
            :let [alias (alias-spec spec)]]
      (t/is (s/get-spec spec))
      (t/is (s/get-spec alias))
      (t/is (= (s/form spec) (s/form alias)))))
  (t/testing "specs"
    (t/testing "scalars"
      (test-specs [:gql/ID :gql/String] {:valid ["str"]
                                         :invalid [1 1.0 true {} () nil :kw]})
      (test-spec :gql/Boolean {:valid [true false]
                               :invalid [1 1.0 {} () nil :kw "str"]})
      (test-spec :gql/Float {:valid [1.0]
                             :invalid [#?(:clj 1) true {} () nil :kw "str"]})
      (test-spec :gql/Int {:valid [1]
                           :invalid [#?(:clj 1.0) true {} () nil :kw "str"]})
      (test-spec :gql/Scalar_Email {:valid ["email@example"]
                                    :invalid ["example.com" 1 1.0 true {} () nil :kw]})
      (test-spec :gql/Scalar_Any {:valid [1 1.0 true {} () nil :kw "str"]}))
    (t/testing "fields and input values"
      (test-specs
        [:gql.InputObject_EmailOrUsername/email
         :gql.Interface_EmailOrUsername/email
         :gql.Object_EmailOrUsername/email]
        {:valid ["email@example" nil]
         :invalid ["example"]})
      (test-spec :gql.Mutation/createUser {:valid ["ID"]
                                           :invalid [nil 1 true]})
      (test-spec :gql.Query/randPosInt {:valid [1 42]
                                        :invalid [nil 0 -42]})
      (test-spec :gql.Query.randPosInt/seed {:valid [1 0 -1]
                                             :invalid [nil "str"]})
      (test-spec :gql.Query/__typename {:valid ["Query"]
                                        :invalid ["str" 1]}))
    (t/testing "objects, interfaces, input objects, and args"
      (test-specs
        [:gql/InputObject_EmailOrUsername
         :gql/Interface_EmailOrUsername
         :gql/Object_EmailOrUsername]
        {:valid [{:id "ID"
                  :email "email@example"}
                 {:id "ID"
                  :username "user"}]
         :invalid [{:id "ID"}
                   {:id "ID"
                    :email "email@example"
                    :username "user"}
                   {:id "ID"
                    :email true}]})
      (test-spec :gql.Query/randPosInt-args {:valid [{:noDefault 1
                                                      :seed 1}]
                                             :invalid [{}
                                                       {:seed nil}
                                                       {:seed true}]}))
    (t/testing "union, union-returning fields, and interface-returning fields"
      (test-specs
        [:gql/Interface_ID
         :gql/Union_ID
         :gql.Query/interfaceID
         :gql.Query/unionID]
        {:valid [{:id "ID"
                  :email "email@example"}
                 {:id "ID"
                  :hasChild false}]
         :invalid [{:id "ID"
                    :hasChild false
                    :child {:id "ID"
                            :hasChild false}}]}))
    (test-specs
      [:gql.InputObject_EmailOrUsername/email
       :gql.Interface_EmailOrUsername/email
       :gql.Object_EmailOrUsername/email]
      {:valid ["foo@bar"]
       :invalid ["foobar"]})))

#?(:cljs (doo.runner/doo-tests))
