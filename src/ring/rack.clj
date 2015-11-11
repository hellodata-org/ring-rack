(ns ring.rack
  (:import  org.jruby.embed.io.WriterOutputStream
           [org.jruby.embed ScriptingContainer LocalContextScope]
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
  {:pre [request-method server-name uri]}
  (let [hash
        (doto
          (.rbClone rack-default-hash)
          (.put "rack.input"        (if body (RewindableInputStream. ^InputStream body)
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

(def buffer-response
  (let [sc (ScriptingContainer.)]
    (.runScriptlet sc
      "def buf(output)
        buffer = java.io.ByteArrayOutputStream.new
        begin
          output.each do |s|
            buffer.write(s.to_java_bytes)
          end
        ensure
          output.close
         end
        buffer
      end")
    sc))

(defn rack-hash->response-map [[status headers body :as response]]
  {:status status
   :headers (->> headers (remove (fn [[k v]] (.startsWith (str k) "rack."))) (into {}))
   :body    #_(org.jruby.util.IOInputStream. body) ;not a legal argument to this wrapper, cause it doesn't respond to "read".
            (java.io.ByteArrayInputStream. (.toByteArray (.callMethod buffer-response nil "buf" body java.io.ByteArrayOutputStream)))})

(defn call-rack-handler [env scripting-container rack-handler]
  (. scripting-container callMethod rack-handler "call" env java.lang.Object))

(defn ring->rack->ring
  "Maps a Ring request to Rack and the response back to Ring spec"
  [request scripting-container rack-default-hash rack-handler]
  (-> request
      (request-map->rack-hash (.. scripting-container getProvider getRuntime) rack-default-hash)
      (call-rack-handler scripting-container rack-handler)
      (rack-hash->response-map)))


;;;
;;; Public API
;;;

(defn wrap-rack-handler
  ([rack-handler]
    (wrap-rack-handler (ScriptingContainer. LocalContextScope/CONCURRENT) rack-handler))

  ([scripting-container rack-handler]
    (let [rack-default-hash (->rack-default-hash scripting-container)]
      (fn [request]
        (ring->rack->ring request scripting-container rack-default-hash rack-handler)))))

(defn boot-rails
  [runtime]
  (.runScriptlet runtime "require 'bundler'")
  (.runScriptlet runtime "require 'rack'")
  (.runScriptlet runtime "app, options = Rack::Builder.parse_file('hello/config.ru'); Rails.application"))


(defn rails-app [runtime]
  (wrap-rack-handler runtime (boot-rails runtime)))

