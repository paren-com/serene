(ns paren.serene-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.test.check]
   [paren.serene :as serene]
   [paren.serene.parser.lacinia]
   [paren.serene.parser.sdl]
   #?(:cljs [doo.runner :include-macros true]))
  #?(:cljs (:require-macros
            [paren.serene-test :refer [test-spec test-specs]])))

(s/check-asserts true)

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
    (namespace kw)
    (str (name kw) "-alias")))

(serene/defschema schema :sdl "paren/serene/schema.graphql"
  :alias alias-spec
  :namespace :gql)

(t/use-fixtures :once
  (fn [run-tests]
    (try
      (serene/def-specs schema)
      (st/instrument)
      (run-tests)
      (finally
        (st/unstrument)
        (serene/undef-specs schema)))))

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
  (t/testing "specs are defined"
    (doseq [spec [;; default types
                  :gql/Boolean
                  :gql/Float
                  :gql/ID
                  :gql/Int
                  :gql/String
                  :gql/__Type
                  :gql/__Schema
                  :gql/__Field
                  :gql/__InputValue
                  :gql/__EnumValue
                  :gql/__Directive
                  :gql/__TypeKind
                  :gql/__DirectiveLocation
                  ;; default fields
                  :gql.Query/__typename
                  :gql.Query/__type
                  :gql.Query/__schema
                  ;; default args
                  :gql.Query.__type/__args
                  :gql.Query.__type/name
                  ;; types
                  :gql/Query
                  :gql/Mutation
                  :gql/Subscription
                  :gql/InputObject_EmailOrUsername
                  :gql/Interface_EmailOrUsername
                  :gql/Interface_ID
                  :gql/Object_EmailOrUsername
                  :gql/Object_IffHasChildThenChild
                  :gql/Scalar_Any
                  :gql/Scalar_Email
                  :gql/Union_ID
                  ;; fields
                  :gql.InputObject_EmailOrUsername/email
                  :gql.Interface_EmailOrUsername/email
                  :gql.Object_EmailOrUsername/email
                  ;; args
                  :gql.Query.randPosInt/__args
                  :gql.Query.randPosInt/seed
                  ;; aliases
                  :gql/Query-alias
                  :gql.Query/__type-alias
                  :gql.Query.__type/__args-alias
                  :gql.Query.__type/name-alias]]
      (t/is (s/get-spec spec))))
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
                                          :invalid [nil "str"]}))
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
      (test-spec :gql.Query.randPosInt/__args {:valid [{:noDefault 1
                                                        :seed 1}]
                                               :invalid [{}
                                                         {:seed nil}
                                                         {:seed true}]}))
    (t/testing "union, union-returning fields, and interface-returning fields"
      (test-specs
        [:gql/Union_ID
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
       :invalid ["foobar"]}))
  (t/testing "aliases"
    (t/is (= (s/form :gql/Query) (s/form :gql/Query-alias)))
    (t/is (= (s/form :gql.Query/__type) (s/form :gql.Query/__type-alias)))
    (t/is (= (s/form :gql.Query.__type/__args) (s/form :gql.Query.__type/__args-alias)))
    (t/is (= (s/form :gql.Query.__type/name) (s/form :gql.Query.__type/name-alias))))
  (t/testing "undef"
    (serene/undef-specs schema)
    (t/is (not (s/get-spec :gql/Query)))))

#?(:cljs (doo.runner/doo-tests))

