(defproject ring-rack "0.0.6-SNAPSHOT"
  :description "Clojure Ring handler implementation of Ruby's Rack webapp spec. Wrap Ruby on Rails in Clojure!"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [ring/ring-core "1.4.0"]
                 [zweikopf "1.0.1" :exclusions [org.jruby/jruby-complete]]
                 [org.jruby/jruby "9.0.0.0"]]
  :global-vars {*warn-on-reflection* true}

  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein gem install rails
  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein rails new hello
  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein bundle install --gemfile=hello/Gemfile
  ;; PATH=`pwd`/target/bundler/bin:$PATH GEM_HOME=`pwd`/target/bundler lein repl
  :aliases {"gem"    ["run" "-m" "org.jruby.Main" "-S" "gem"]
            "bundle" ["run" "-m" "org.jruby.Main" "-S" "bundle"]
            "rails"  ["run" "-m" "org.jruby.Main" "-S" "rails"]})
