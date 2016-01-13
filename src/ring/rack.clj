(ns ring.rack
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
            org.jruby.embed.io.WriterOutputStream
           [org.jruby.embed ScriptingContainer LocalContextScope]
           [org.jruby.runtime CallSite MethodIndex]
            org.jruby.javasupport.JavaEmbedUtils
           [org.jruby Ruby RubyArray RubyHash RubyIO RubyInteger RubyObject])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [ring.middleware.params :refer (params-request)]
            [ring.middleware.nested-params :as p]
            [zweikopf.multi :as zw]))

;; Ring decided to make this pure function private...
(def nest-params #'p/nest-params)


(def ^CallSite rack-call       (MethodIndex/getFunctionalCallSite "call"))

(defn ruby-fn [^ScriptingContainer sc ruby-code-string]
  (.runScriptlet sc
    (str "Class.new(Java::clojure.lang.AFn) {
            def invoke" ruby-code-string "
            end
          }.new()")))


(defn ^ScriptingContainer new-scripting-container []
  (ScriptingContainer. LocalContextScope/CONCURRENT))

(defn ^ScriptingContainer require-rack [^ScriptingContainer sc]
  (println (.getClassLoader sc))
  (.setLoadPaths sc (concat (.getLoadPaths sc)
                            [(.toExternalForm (io/resource "rack-1.6.4/lib"))
                             (.toExternalForm (io/resource "rack-1.6.4/lib"))]))
  (.runScriptlet sc "
    require 'stringio'
    require 'rack'
    require 'rack/rewindable_input'")
  sc)


(def rewindable-fn (memoize
  (fn [scripting-container]
    (ruby-fn scripting-container "(inputs) Rack::RewindableInput.new(inputs.to_io)"))))

(def responsify-fn (memoize
  (fn [scripting-container]
    (ruby-fn scripting-container "(output)
      begin
        retval = []
        binary = false

        output.each do |s|
          #print(s.encoding, \" of \", s.size, \" bytes\\n\")
          retval.push(
            if s.respond_to?(:encoding) && Encoding::BINARY == s.encoding
              binary = true
              Java::java.io.ByteArrayInputStream.new( s.to_java_bytes )
            else
              binary = true if s.is_a?(File)
              s
            end)
        end

        if retval.size == 1
          retval[0].to_java
        elsif binary
          retval.map! do |s|
            if s.java_kind_of?(Java::java.io.InputStream)
              s
            elsif s.is_a?(File)
              Java::java.io.FileInputStream.new(s.to_java)
            elsif s.is_a?(String)
              StringIO.new(s).to_inputstream
            else
              throw \"Unsupported to return multiple byte-strings mixed with: #{s}\"
            end
          end
          Java::java.io.SequenceInputStream.new(Java::java.util.Collections.enumeration(retval))
        else
          Java::clojure.lang.RT.seq(retval)
        end
      ensure
        output.close if output.respond_to?(:close)
      end"))))

(defn ->rack-default-hash [^ScriptingContainer rs]
  (doto (RubyHash. (.. rs getProvider getRuntime))
        (.put "clojure.version"   (clojure-version))
        (.put "jruby.version"     (.runScriptlet rs "JRUBY_VERSION"))
        (.put "rack.version"      (.runScriptlet rs "::Rack::VERSION"))
        (.put "rack.input"        (.runScriptlet rs "StringIO.new(''.encode(Encoding::ASCII_8BIT))"))
        (.put "rack.multithread"  true)
        (.put "rack.multiprocess" false)
        (.put "rack.run_once"     false)
        (.put "rack.hijack?"      false)
        (.put "SCRIPT_NAME"       "")))

(defn ->RubyIO [value]
  (condp instance? value))

(declare params-map->ruby-hash)

(defn params-map-value->ruby-value [v ^ScriptingContainer rs]
  (cond
    (vector? v)
      (let [num-items  (count v)
            ruby-array (RubyArray/newArray (.getRuntime rs) num-items)]
        (loop [i 0]
          (when (< i num-items)
            (.set ruby-array i
                  (params-map-value->ruby-value (nth v i) rs))
            (recur (inc i))))
        ruby-array)

    :else
      (zw/rubyize v rs)))

(defn params-map->ruby-hash [params ^ScriptingContainer rs]
  (let [hash (RubyHash. (.. rs getProvider getRuntime))]
    (doseq [[k v] params]
      (.put hash (name k) (params-map-value->ruby-value v rs)))
    hash))

(defn keep-last-string-values [params]
  "Usage:
   => (keep-last-string-values {\"form[name]\" [\"1\" \"2\"] \"form[field]\" \"3\"})
   {\"form[name]\" \"2\", \"form[field]\" \"3\"}
   => (keep-last-string-values {:a [1 2 3]})
   {:a [1 2 3]}"
  (zipmap (keys params)
          (map (fn [[k v]] (if (and (vector? v)
                                    (string? k)
                                    (not (.endsWith k "[]")))
                             (last v)
                             #_else v))
               params)))

(defn duplicate-stream
  [stream]
  (let [buffer (ByteArrayOutputStream.)
        _ (io/copy stream buffer)
        bytes (.toByteArray buffer)]
    {:stream1 (ByteArrayInputStream. bytes)
     :stream2 (ByteArrayInputStream. bytes)}))

(defn request-map->rack-hash [{:keys [request-method server-name uri body] :as request}
                              ^ScriptingContainer scripting-container
                              ^RubyHash rack-default-hash
                              rewindable]
  {:pre [request-method server-name uri]}
  (let [runtime (.. scripting-container getProvider getRuntime)
        {:keys [stream1 stream2]} (when (:body request) (duplicate-stream body))
        {:keys [request-method uri query-string headers form-params
                scheme server-name server-port remote-addr]} (params-request (assoc request :body stream1))
        body-input (when stream2 (rewindable stream2))
        hash
        (doto
          ^RubyHash (.rbClone rack-default-hash)
          (.put "rack.errors"       (RubyIO. runtime (WriterOutputStream. *err*)))
          (.put "rack.url_scheme"   (if scheme (name scheme) #_else "http"))
          (.put "REQUEST_METHOD"    (case request-method
                                      :get "GET", :post "POST", :put "PUT", :patch, "PATCH", :delete "DELETE"
                                      (-> request-method name str/upper-case)))
          (.put "REQUEST_URI"       uri)
          (.put "PATH_INFO"         uri)
          (.put "QUERY_STRING"      (or query-string ""))
          (.put "SERVER_NAME"       server-name)
          (.put "SERVER_PORT"       (or (str server-port) "80"))
          (.put "REMOTE_ADDR"       remote-addr))]
    (when body-input
      (.put hash "rack.input" body-input))
    (when-not (empty? form-params)
      (.put hash "rack.request.form_input" body-input)
      ;; We don't set RACK_REQUEST_FORM_VARS, is this bad?
      ;(.put hash "rack.request.form_vars" ...)
      (.put hash "rack.request.form_hash"
            (-> form-params
                (keep-last-string-values)
                (nest-params p/parse-nested-keys)
                (params-map->ruby-hash scripting-container))))
    (when-let [content-length (some->> (get headers "content-length") (re-matches #"[0-9]+"))]
      (.put hash "CONTENT_LENGTH" content-length))
    (when-let [content-type (get headers "content-type")]
      (when-not (empty? content-type)
        (.put hash "CONTENT_TYPE" content-type)))
    ;; Put HTTP_ header variables
    (doseq [[name value] headers :when (not (#{"content-type" "content-length"} name))]
      (.put hash (str "HTTP_" (->> (.split (str name) "-")  (map str/upper-case) (str/join "_"))) value))
    ;; All done!
    hash))

(defn charset [content-type charset]
  (-> (or content-type "text/plain")
      (str/replace #";\s*charset=[^;]*" "")
      (str "; charset=" charset)))

(defn rack-hash->response-map [[status headers body :as response] responsify]
  (let [status  (if (number? status) status #_else (Integer/parseInt (str status)))
        body    (responsify body)
        headers (->> headers
                     (remove (fn [[k v]] (.startsWith (str k) "rack.")))
                     (reduce conj! (transient {})))
        headers (if (string? body)
                  (-> headers
                      (assoc! "Content-Length" (-> body str (.getBytes "UTF-8") count str))
                      (assoc! "Content-Type"   (charset (get headers "Content-Type") "UTF-8")))
                 #_else headers)]
    {:status  status
     :headers (persistent! headers)
     :body    body}))

(defn call-rack-handler [^RubyHash env ^ScriptingContainer scripting-container
                         ^RubyObject rack-handler]
  (.call rack-call (.. scripting-container getProvider getRuntime getCurrentContext)
         rack-handler rack-handler env))


(defn ring->rack->ring
  "Maps a Ring request to Rack and the response back to Ring spec"
  [request scripting-container rack-default-hash rack-handler rewindable responsify]
  (-> (request-map->rack-hash request scripting-container rack-default-hash rewindable)
      (call-rack-handler scripting-container rack-handler)
      (rack-hash->response-map responsify)))


(defn boot-rack
  [path-to-app ^ScriptingContainer scripting-container]
  (.setLoadPaths scripting-container (concat (.getLoadPaths scripting-container) [path-to-app]))
  (.runScriptlet scripting-container
    (str "require 'bundler'
          require 'rack'
          app, options = Rack::Builder.parse_file('" path-to-app "/config.ru')
          app")))


;;;
;;; Public API
;;;

(defn wrap-rack-handler
  ([rack-handler]
    (wrap-rack-handler rack-handler (new-scripting-container)))

  ([rack-handler scripting-container]
    (require-rack scripting-container)
    (let [rack-default-hash (->rack-default-hash scripting-container)
          rewindable (rewindable-fn scripting-container)
          responsify (responsify-fn scripting-container)]
      (fn [request]
        (ring->rack->ring request scripting-container rack-default-hash
                          rack-handler rewindable responsify)))))

(defn rack-app
  ([path-to-app]
    (rack-app path-to-app (new-scripting-container)))

  ([path-to-app scripting-container]
    (-> (boot-rack path-to-app scripting-container)
        (wrap-rack-handler scripting-container))))
