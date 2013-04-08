(ns cfpb.qu.views
  "Functions to display resource data in HTML, CSV, and JSON formats."
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :refer [info error]]
   [clojure
    [string :as str]
    [pprint :refer [pprint]]]
   [compojure
    [route :as route]
    [response :refer [render]]]
   [noir.validation :as valid]
   [clojure-csv.core :refer [write-csv]]
   [monger
    [core :as mongo :refer [get-db with-db]]
    [collection :as coll]
    json]
   [net.cgrand.enlive-html
    :as html
    :refer [deftemplate defsnippet]]
   [ring.util.response :refer [content-type]]
   ring.middleware.content-type
   [noir.response :as response]
   [cfpb.qu.data :as data]
   [cfpb.qu.query :as query]
   [cfpb.qu.query.select :as select]))

(defn json-error
  ([status] (json-error status {}))
  ([status body]
     (response/status
      status
      (response/json body))))

(deftemplate layout-html "templates/layout.html"
  [content]

  [:div#content]
  (html/content content))

(defsnippet index-html "templates/index.html" [:#content]
  [datasets]

  [:ul#dataset-list :li]
  (html/clone-for [dataset datasets]
                  [:a]
                  (html/do->
                   (html/content (str (get-in dataset [:info :name])))
                   (html/set-attr :href (str "/data/" (:name dataset))))

                  [:small]
                  (html/content (get-in dataset [:info :description]))))

(defsnippet not-found-html "templates/404.html" [:#content]
  [msg]

  [:.message]
  (html/content msg))

(defsnippet dataset-html "templates/dataset.html" [:#content]
  [dataset metadata]

  [:h1 html/any-node]
  (html/replace-vars {:dataset dataset})

  [:h1 :a.index]
  (html/set-attr :href "/data")

  [:ul#slices :li]
  (html/clone-for [slice (map name (keys (:slices metadata)))]
                  [:a]
                  (html/do->
                   (html/content slice)
                   (html/set-attr :href (str "/data/" dataset "/" slice))))

  [:pre.definition]
  (html/content (with-out-str (pprint metadata))))

(defn select-fields
  "In API requests, the user can select the columns they want
  returned. If they choose to do this, the columns will be in a
  comma-separated string. This function returns a seq of column names
  from that string."
  [select]
  (if select
    (->> (select/parse select)
         (map :select))))

(defn- columns-for-view [slice-def params]
  (let [select (:$select params)]
    (if (and select
             (not= select ""))
      (select-fields select)
      (data/slice-columns slice-def))))

(defn- fill-in-input-value [params]
  (fn [node]
    (let [field (keyword (get-in node [:attrs :data-clause]))]
      (html/at node
               [:input]
               (html/set-attr :value (params field))))))

(defn- highlight-errors []
  (fn [node]
    (let [field (keyword (get-in node [:attrs :data-clause]))]
      (if (valid/errors? field)
        ((html/do->
          (html/add-class "error")
          (html/append (map (fn [error] {:tag :span
                                         :attrs {:class "help-inline"}
                                         :content error})
                            (valid/get-errors field))))
         node)
        node))))

(defsnippet slice-html "templates/slice.html" [:#content]
  [params action dataset metadata slice-def columns data]

  [:form#query-form]
  (html/do->
   (html/set-attr :action action)
   (html/set-attr :data-href action))

  [:h1 html/any-node]
  (html/replace-vars {:dataset dataset})

  [:h1 :a.index]
  (html/set-attr :href "/data")

  [:h1 :a.dataset]
  (html/set-attr :href (str "/data/" dataset))

  [:.metadata html/any-node]
  (html/replace-vars {:dimensions (str/join ", " (:dimensions slice-def))
                      :metrics (str/join ", " (:metrics slice-def))})

  [:.dimension-fields :.dimension-field]
  (html/clone-for [dimension (:dimensions slice-def)]
                  [:label]
                  (html/do->
                   (html/content (data/concept-description metadata dimension))
                   (html/set-attr :for (str "field-" dimension)))

                  [:input]
                  (html/do->
                   (html/set-attr :name dimension)
                   (html/set-attr :id (str "field-" dimension))
                   (html/set-attr :value (or (params (keyword dimension))
                                             (params dimension)))))

  [:.clause-fields :.control-group]
  (html/do->
   (fill-in-input-value params)
   (highlight-errors))
  
  [:#query-results :thead :tr]
  (html/content (html/html
                 (for [column columns]
                   [:th (name (data/concept-description metadata column))])))

  [:#query-results :tbody :tr]
  (html/clone-for [row data]
                  (html/content (html/html
                                 (for [value row]
                                   [:td value])))))

(defmulti slice (fn [format _ _]
                  format))

(defmethod slice "text/html" [_ data {:keys [dataset slice-def params headers]}]
  (let [metadata (data/get-metadata dataset)
        slice-name (:slice params)
        action (str "http://" (headers "host") "/data/" dataset "/" slice-name)
        columns (columns-for-view slice-def params)
        data (data/get-data-table data columns)]

    (apply str (layout-html (slice-html params
                                        action
                                        dataset
                                        metadata
                                        slice-def
                                        columns
                                        data)))))

(defmethod slice "application/json" [_ data _]
  (response/json data))

(defmethod slice "text/csv" [_ data {:keys [slice-def params]}]
  (let [table (:table slice-def)
        columns (columns-for-view slice-def params)
        rows (data/get-data-table data columns)]
    (response/content-type
     "text/csv; charset=utf-8"
     (str (write-csv (vector columns)) (write-csv rows)))))

(defmethod slice :default [format _ _]
  (response/status
   406
   (response/content-type
    "text/plain"
    (str "Format not found: " format
         ". Valid formats are application/json, text/csv, and text/html."))))