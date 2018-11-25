(task-options!
  pom {:project 'com.paren/serene
       :version "0.0.1-RC3"
       :description "Generate clojure.spec with GraphQL and extend GraphQL with clojure.spec"
       :url "https://github.com/paren-com/serene"
       :scm {:url "https://github.com/paren-com/serene"}
       :license {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v20.html"}})

(set-env!
  :source-paths #{}
  :resource-paths #{"sources/main"
                    "resources/main"})

(merge-env!
  :dependencies [['org.clojure/clojure (clojure-version)]])

(merge-env!
  :dependencies '[[adzerk/boot-cljs "2.1.5" :scope "test"]
                  [com.walmartlabs/lacinia "0.31.0-SNAPSHOT-1" :scope "test"]
                  [crisptrutski/boot-cljs-test "0.3.5-SNAPSHOT" :scope "test"]
                  [doo "0.1.8" :scope "test"]
                  [expound "0.7.1" :scope "test"]
                  [fipp "0.6.14"]
                  [http-kit "2.3.0"]
                  [metosin/bat-test "0.4.0" :scope "test"]
                  [org.clojure/clojurescript "1.10.439"]
                  [org.clojure/data.json "0.2.6"]
                  [org.clojure/spec.alpha "0.2.176"]
                  [org.clojure/test.check "0.10.0-alpha3" :scope "test"]
                  [samestep/boot-refresh "0.1.0" :scope "test"]])

(require
  '[crisptrutski.boot-cljs-test :refer [test-cljs]]
  '[metosin.bat-test :refer [bat-test]]
  '[samestep.boot-refresh :refer [refresh]])

(deftask build []
  (comp
    (pom)
    (jar)
    (install)))

(deftask deploy []
  (comp
    (build)
    (push :repo "clojars")))

(deftask test-env []
  (merge-env! :resource-paths #{"sources/test"
                                "resources/test"})
  identity)

(ns-unmap *ns* 'test)
(deftask test []
  (comp
    (test-env)
    (bat-test)
    (test-cljs
      :cljs-opts {:parallel-build true}
      :ids ["paren/serene_test"]
      :js-env :node)))

(deftask dev-env []
  (merge-env! :resource-paths #{"sources/dev"
                                "resources/dev"})
  identity)

(deftask dev []
  (comp
    (dev-env)
    (repl :server true)
    (watch)
    (refresh)))
