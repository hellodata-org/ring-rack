(ns ring.rack
  (:import org.jruby.embed.io.WriterOutputStream
           org.jruby.javasupport.JavaEmbedUtils
           org.jruby.RubyIO
           org.jruby.rack.servlet.RewindableInputStream)
  (:require [clojure.string :as str]))

(defn ^java.util.Map ->rack-default-hash [^org.jruby.embed.ScriptingContainer rs]
  (.runScriptlet rs "require 'rack'")
  (doto (org.jruby.RubyHash. (.. rs getProvider getRuntime))
        (.put "clojure.version"   (clojure-version))
        (.put "jruby.version"     (.runScriptlet rs "JRUBY_VERSION"))
        (.put "rack.version"      (.runScriptlet rs "::Rack::VERSION"))
        (.put "rack.multithread"  true)
        (.put "rack.multiprocess" false)
        (.put "rack.run_once"     false)
        (.put "rack.hijack?"      false)
        (.put "SCRIPT_NAME"       "")))

(defn ->RubyIO [value]
  (condp instance? value ))

(defn request-map->rack-hash [{:keys [request-method uri query-string body headers
                                      scheme server-name server-port remote-addr] :as request}
                              runtime rack-default-hash]
  (let [hash
        (doto
          (.rbClone rack-default-hash)
          (.put "rack.input"        (if body (RewindableInputStream. ^InputStream body)
                                     #_else "" ))
          (.put "rack.errors"       (RubyIO. runtime (WriterOutputStream. *err*)))
          (.put "rack.url_scheme"   (name scheme))
          (.put "REQUEST_METHOD"    (case request-method
                                      :get "GET", :post "POST", :put "PUT", :delete "DELETE"
                                      (-> request-method name str/upper-case)))
          (.put "REQUEST_URI"       uri)
          (.put "PATH_INFO"         uri)
          (.put "QUERY_STRING"      (or query-string ""))
          (.put "SERVER_NAME"       server-name)
          (.put "SERVER_PORT"       (or server-port "80"))
          (.put "REMOTE_ADDR"       remote-addr))]
    (when-let [content-length (re-matches #"[0-9]+" (get headers "content-length"))]
      (.put "CONTENT_LENGTH" content-length))
    (when-let [content-type (get headers "content-type")]
      (.put "CONTENT_TYPE" content-type))
    ;; Put HTTP_ header variables
    (doseq [[name value] headers :when (not #{"content-type" "content-length"} name)]
      (.put (->> (.split (str name) "-")  (map str/upper-case) (str/join "_")) value))
    ;; All done!
    hash))


(defn rack-hash->response-map [[status headers body :as response]]
  {:status status
   :headers (->> headers (remove (fn [[k v]] (.startsWith (str k) "rack."))) (into {}))
   :body    (JavaEmbedUtils/rubyToJava body)})


(defn ring->rack->ring
  "Maps a Ring request to Rack and the response back to Ring spec"
  [request runtime rack-default-hash rack-handler]
  (-> request
      (request-map->rack-hash rack-default-hash runtime)
      (rack-handler rack-default-hash)
      (rack-hash->response-map)))


;;;
;;; Public API
;;;

(defn wrap-rack-handler
  ([rack-handler]
    (wrap-rack-handler (org.jruby.embed.ScriptingContainer.)))

  ([runtime rack-handler]
    (let [rack-default-hash (->rack-default-hash runtime)]
      (fn [request]
        (ring->rack->ring request runtime rack-default-hash rack-handler)))))

(defn rails-app
  ;; https://github.com/jruby/jruby-rack/blob/master/src/main/java/org/jruby/rack/rails/RailsRackApplicationFactory.java
  ""
  []
  (wrap-rack-handler #_rails-handler))
