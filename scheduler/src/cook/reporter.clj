;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns cook.reporter
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [datomic.api :as d :refer (q)]
            [metatransaction.core :as mt :refer (db)]
            [metrics.core :as metrics])
  (:import [com.codahale.metrics.graphite GraphiteReporter PickledGraphite Graphite]
           com.codahale.metrics.MetricFilter
           [com.codahale.metrics.riemann Riemann RiemannReporter]
           com.aphyr.riemann.client.RiemannClient
           java.util.concurrent.TimeUnit
           java.net.InetSocketAddress))

;; the default registry
(def registry metrics/default-registry)

(defn jmx-reporter
  []
  (.. (com.codahale.metrics.JmxReporter/forRegistry metrics/default-registry)
      (build)
      (start)))

(defn graphite-reporter
  [{:keys [prefix host port pickled?]}]
  (let [addr (InetSocketAddress. host port)
        graphite (if pickled?
                   (PickledGraphite. addr)
                   (Graphite. addr))]
    (doto (.. (GraphiteReporter/forRegistry metrics/default-registry)
              (prefixedWith prefix)
              (filter MetricFilter/ALL)
              (convertRatesTo TimeUnit/SECONDS)
              (convertDurationsTo TimeUnit/MILLISECONDS)
              (build graphite))
      (.start 30 TimeUnit/SECONDS))))

(defn riemann-reporter
  [{:keys [host port tags prefix mode local-host] :or {tags [] mode :tcp} :as cfg}]
  (when (= mode :udp)
    (throw (ex-info "You shouldn't use UDP mode Riemann! Almost every user finds it annoying when, without TCP backpressure, they start losing critical metrics during failure events." {})))
  (let [addr (InetSocketAddress. host port)
        riemann-client (case mode
                         :tcp (RiemannClient/tcp host port)
                         :udp (RiemannClient/udp addr)
                         (throw (ex-info "Mode must be :tcp or :udp" cfg)))]
    (.connect riemann-client)
    (doto (.. (RiemannReporter/forRegistry metrics/default-registry)
              (localHost local-host)
              (prefixedWith prefix)
              (filter MetricFilter/ALL)
              (convertRatesTo TimeUnit/SECONDS)
              (convertDurationsTo TimeUnit/MILLISECONDS)
              (withTtl (float 60))
              (tags tags)
              (build (Riemann. riemann-client)))
      (.start 30 TimeUnit/SECONDS))))
