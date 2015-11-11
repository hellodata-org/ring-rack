(defproject clj-jruby-rack "0.1.0-SNAPSHOT"
  :description "Clojure Ring handler implementation of Ruby's Rack webapp spec"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.jruby/jruby "9.0.0.0"]
                 [org.jruby.rack/jruby-rack "1.1.18"]
                 [javax.servlet/servlet-api "2.5"]
                 [zweikopf "1.0.1" :exclusions [org.jruby/jruby-complete]]
                ])
