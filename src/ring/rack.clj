(ns ring.rack
  (:import  org.jruby.embed.io.WriterOutputStream
           [org.jruby.embed ScriptingContainer LocalContextScope]
           [org.jruby.runtime CallSite MethodIndex]
            org.jruby.javasupport.JavaEmbedUtils
           [org.jruby Ruby RubyHash RubyIO RubyInteger RubyObject])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(def ^CallSite rewindable-call (MethodIndex/getFunctionalCallSite "rewindable"))
(def ^CallSite responsify-call (MethodIndex/getFunctionalCallSite "responsify"))
(def ^CallSite rack-call       (MethodIndex/getFunctionalCallSite "call"))

(def ^ScriptingContainer ruby-helpers
  (let [sc (ScriptingContainer.)]
    (.setLoadPaths sc (concat (.getLoadPaths sc)
                              [(.toExternalForm (io/resource "rack-1.6.4/lib"))]))
    (.runScriptlet sc "
      require 'rack'
      require 'rack/rewindable_input'

      def rewindable(input)
        Rack::RewindableInput.new(input)
      end

      def responsify(output)
        begin
          return output[0].to_java if output.respond_to?(:size) && output.size > 1
          output.each{|s| s.to_java}
        ensure
          output.close if output.respond_to?(:close)
         end
      end")
    sc))

(def ^Ruby helper-runtime
  (.. ruby-helpers getProvider getRuntime))

(defn rewindable [^java.io.InputStream input-stream ^Ruby runtime]
  (.call rewindable-call (.getCurrentContext runtime)
         (.getTopSelf runtime) (.getTopSelf helper-runtime) (RubyIO. runtime input-stream)))

(defn responsify [^RubyObject body]
  (-> (.call responsify-call (.getCurrentContext helper-runtime)
             (.getTopSelf helper-runtime) (.getTopSelf helper-runtime) body)
      (seq)))

(defn new-scripting-container []
  (ScriptingContainer. LocalContextScope/CONCURRENT))

(defn ->rack-default-hash [^ScriptingContainer rs]
  (.runScriptlet rs "require 'rack'")
  (doto (RubyHash. (.. rs getProvider getRuntime))
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
                              ^Ruby runtime ^RubyHash rack-default-hash]
  {:pre [request-method server-name uri]}
  (let [hash
        (doto
          ^RubyHash (.rbClone rack-default-hash)
          (.put "rack.input"        (if body (rewindable body runtime)
                                     #_else "" ))
          (.put "rack.errors"       (RubyIO. runtime (WriterOutputStream. *err*)))
          (.put "rack.url_scheme"   (if scheme (name scheme) #_else "http"))
          (.put "REQUEST_METHOD"    (case request-method
                                      :get "GET", :post "POST", :put "PUT", :delete "DELETE"
                                      (-> request-method name str/upper-case)))
          (.put "REQUEST_URI"       uri)
          (.put "PATH_INFO"         uri)
          (.put "QUERY_STRING"      (or query-string ""))
          (.put "SERVER_NAME"       server-name)
          (.put "SERVER_PORT"       (or server-port "80"))
          (.put "REMOTE_ADDR"       remote-addr))]
    (when-let [content-length (some->> (get headers "content-length") re-matches #"[0-9]+")]
      (.put hash "CONTENT_LENGTH" content-length))
    (when-let [content-type (get headers "content-type")]
      (when-not (empty? content-type)
        (.put hash "CONTENT_TYPE" content-type)))
    ;; Put HTTP_ header variables
    (doseq [[name value] headers :when (not (#{"content-type" "content-length"} name))]
      (.put hash (->> (.split (str name) "-")  (map str/upper-case) (str/join "_")) value))
    ;; All done!
    hash))

(defn rack-hash->response-map [[status headers body :as response]]
  {:status  status
   :headers (->> headers (remove (fn [[k v]] (.startsWith (str k) "rack."))) (into {}))
   :body    (responsify body)})

(defn call-rack-handler [^RubyHash env ^ScriptingContainer scripting-container
                         ^RubyObject rack-handler]
  (.call rack-call (.. scripting-container getProvider getRuntime getCurrentContext)
         rack-handler rack-handler env))

(defn ring->rack->ring
  "Maps a Ring request to Rack and the response back to Ring spec"
  [request ^ScriptingContainer scripting-container rack-default-hash rack-handler]
  (-> request
      (request-map->rack-hash (.. scripting-container getProvider getRuntime) rack-default-hash)
      (call-rack-handler scripting-container rack-handler)
      (rack-hash->response-map)))


(defn boot-rails
  [path-to-app ^ScriptingContainer scripting-container]
  (.setLoadPaths scripting-container (concat (.getLoadPaths scripting-container) [path-to-app]))
  (.runScriptlet scripting-container
    (str "require 'bundler'
          require 'rack'
          app, options = Rack::Builder.parse_file('" path-to-app "/config.ru')
          Rails.application")))


;;;
;;; Public API
;;;

(defn wrap-rack-handler
  ([rack-handler]
    (wrap-rack-handler rack-handler (new-scripting-container)))

  ([rack-handler scripting-container]
    (let [rack-default-hash (->rack-default-hash scripting-container)]
      (fn [request]
        (ring->rack->ring request scripting-container rack-default-hash rack-handler)))))

(defn rails-app
  ([path-to-app]
    (rails-app path-to-app (new-scripting-container)))

  ([path-to-app scripting-container]
    (-> (boot-rails path-to-app scripting-container)
        (wrap-rack-handler scripting-container))))
