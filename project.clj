(defproject clj-jruby-rack "0.1.0-SNAPSHOT"
  :description "Clojure Ring handler implementation of Ruby's Rack webapp spec"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.jruby/jruby "9.0.0.0"]
                 [org.jruby.rack/jruby-rack "1.1.18" :exclusions [org.jruby/jruby-complete]]
                 [javax.servlet/servlet-api "2.5"]
                 [zweikopf "1.0.1" :exclusions [org.jruby/jruby-complete]]
                ]
  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein gem install rails
  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein rails new hello
  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein bundle install --gemfile=hello/Gemfile
  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein repl
  :aliases {"gem"    ["run" "-m" "org.jruby.Main" "-S" "gem"]
            "bundle" ["run" "-m" "org.jruby.Main" "-S" "bundle"]
            "rails"  ["run" "-m" "org.jruby.Main" "-S" "rails"]})
