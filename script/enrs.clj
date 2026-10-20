(ns enrs
  "Downloads NC State Board of Elections absentee data from
  https://dl.ncsbe.gov/list.html?prefix=ENRS/ and turns it into daily early
  vote counts for early-vote-counts.csv. Each election's absentee zip has a
  row per absentee ballot, which includes early voting (one-stop) ballots"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.util.zip ZipInputStream)))

(def county "DURHAM")
(def counts-file "early-vote-counts.csv")
(def tmp-dir "tmp")
(def bucket "https://s3.amazonaws.com/dl.ncsbe.gov")

(def ^:private year-cli
  {:spec {:year {:positional true :require true :coerce :string :complete false
                 :desc "Year of a November election e.g. 2024"
                 :validate {:pred #(re-matches #"\d{4}" %)
                            :ex-msg #(str "Invalid year: " (:value %))}}
          :force {:coerce :boolean :desc "Redownload even if the zip is in tmp/"}}
   :args->opts [:year]
   :restrict true})

(defn- local-zip
  "Returns the downloaded zip for `year`'s November election, if any"
  [year]
  (first (fs/glob tmp-dir (str "absentee_" year "11[0-9][0-9].zip"))))

(defn- remote-zip
  "Finds the absentee zip for `year`'s November election by listing ENRS"
  [year]
  (let [prefixes (->> (http/get bucket {:query-params {"delimiter" "/" "prefix" "ENRS/"}})
                      :body
                      (re-seq #"<Prefix>(ENRS/(\d{4})_(\d{2})_(\d{2})/)</Prefix>"))
        [_ _ y m d] (or (first (filter (fn [[_ _ y m]] (and (= y year) (= m "11"))) prefixes))
                        (throw (ex-info (str "No November election for " year " in ENRS") {})))
        date (str y m d)]
    (str "https://dl.ncsbe.gov/ENRS/" y "_" m "_" d "/absentee_" date ".zip")))

(defn download-year
  "Downloads a year's absentee zip to tmp/ if it isn't there yet
  It downloads to a .part file first so an interrupted download isn't
  mistaken for a finished one"
  {:org.babashka/cli year-cli}
  [{:keys [year force]}]
  (if-let [zip (and (not force) (local-zip year))]
    (println "Using" (str zip))
    (let [url (remote-zip year)
          dest (fs/path tmp-dir (fs/file-name url))
          part (fs/path tmp-dir (str (fs/file-name url) ".part"))]
      (fs/create-dirs tmp-dir)
      (println "Downloading" url "...")
      (with-open [in (:body (http/get url {:as :stream}))]
        (io/copy in (fs/file part)))
      (fs/move part dest {:replace-existing true})
      (println "Saved" (str dest)))))

(def ^:private mdy (DateTimeFormatter/ofPattern "MM/dd/yyyy"))

(defn- election-date
  "Parses the election date out of a name like absentee_20241105.zip"
  [zip]
  (LocalDate/parse (second (re-find #"(\d{8})" (fs/file-name zip)))
                   DateTimeFormatter/BASIC_ISO_DATE))

(defn- parse-row
  "Parses a csv line, falling back to splitting quoted fields for the odd row
  with an unescaped quote, e.g. an overseas address of `\"41\"4\"`. Files
  before 2008 are tab separated"
  [line tabs?]
  (if tabs?
    (mapv #(str/replace % #"^\"|\"$" "") (str/split line #"\t"))
    (try (first (csv/read-csv line))
         (catch Exception _
           (str/split (subs line 1 (dec (count line))) #"\",\"")))))

(def ^:private early-types
  "Values of an early voting ballot over the years, newest first"
  #{"EARLY VOTING" "ONE-STOP" "ONE STOP" "OS"})

(defn- parse-date
  "Parses a ballot date of 10/20/2022 or, before 2010, 20061019"
  [s]
  (let [s (str/trim s)]
    (when-not (str/blank? s)
      (LocalDate/parse s (if (str/includes? s "/") mdy DateTimeFormatter/BASIC_ISO_DATE)))))

(defn daily-counts
  "Returns `[[date count] ...]` of accepted early voting ballots for `county`
  per day of `year`. Days without voting between the first and last day count
  as 0. Ballots dated outside the 4 weeks before election day are ignored as
  data entry mistakes, e.g. a lone ballot returned 09/01/2018. Files before
  2006 have no return status so all their early voting ballots count"
  [year]
  (let [zip (or (local-zip year) (throw (ex-info (str "No zip for " year) {})))
        election (election-date zip)
        counts (with-open [zin (ZipInputStream. (io/input-stream (fs/file zip)))]
                 (.getNextEntry zin)
                 (let [lines (line-seq (io/reader zin :encoding "ISO-8859-1"))
                       tabs? (str/includes? (first lines) "\t")
                       ;; Headers vary in case and spacing e.g. "Voter City"
                       header (mapv #(str/replace (str/lower-case (str/trim %)) " " "_")
                                    (parse-row (first lines) tabs?))
                       col (fn [& ks] (first (keep #(let [i (.indexOf header %)] (when-not (neg? i) i)) ks)))
                       county-i (col "county_desc")
                       type-i (col "ballot_req_type" "ballot_request_type")
                       status-i (col "ballot_rtn_status")
                       date-i (col "ballot_rtn_dt")
                       cell #(some-> (get %1 %2) str/trim)]
                   (when-not (and county-i type-i date-i)
                     (throw (ex-info "Unexpected absentee columns" {:header header})))
                   (->> (rest lines)
                        ;; Cheap check before parsing ~2 million rows of csv
                        (filter #(str/includes? % county))
                        (map #(parse-row % tabs?))
                        (filter #(and (= county (cell % county-i))
                                      (early-types (cell % type-i))
                                      (or (nil? status-i)
                                          (#{"ACCEPTED" "OK"} (cell % status-i)))))
                        (keep #(parse-date (get % date-i "")))
                        (filter #(and (.isBefore % election)
                                      (not (.isBefore % (.minusWeeks election 4)))))
                        frequencies)))
        dates (sort (keys counts))
        days (->> (iterate #(.plusDays % 1) (first dates))
                  (take-while #(and % (not (.isAfter % (last dates))))))]
    (mapv (fn [d] [d (get counts d 0)]) days)))

(defn show-year
  "Prints a year's daily early vote counts for Durham county"
  {:org.babashka/cli year-cli}
  [{:keys [year]}]
  (let [days (daily-counts year)]
    (printf "%s early voting in %s county\n" year county)
    (doseq [[i [d n]] (map-indexed vector days)]
      (printf "Day %2d  %s %s  %,7d\n" (inc i) (subs (str (.getDayOfWeek d)) 0 3)
              (.format d mdy) n))
    (printf "Total           %,7d\n" (reduce + (map second days)))
    (flush)))

(defn- in-progress?
  "Returns true when early voting for `year` hasn't finished, so its column
  gets empty cells instead of trailing 0s"
  [year]
  (not (.isBefore (election-date (local-zip year)) (LocalDate/now))))

(defn add-year
  "Adds or replaces a year's row in early-vote-counts.csv
  Rows stay sorted by year. Finished years are padded with trailing 0s to the longest year and
  years in progress are left shorter, as data/parse-counts expects"
  {:org.babashka/cli year-cli}
  [{:keys [year]}]
  (let [counts (mapv (comp str second) (daily-counts year))
        [_ & rows] (csv/read-csv (slurp counts-file))
        old-days (apply max 0 (map (comp dec count) rows))
        ;; A row shorter than the longest is a year in progress
        progress (set (keep #(when (< (dec (count %)) old-days) (first %)) rows))
        years (-> (into (sorted-map) (map (juxt first (comp vec rest)) rows))
                  (assoc year counts))
        progress (cond-> (disj progress year) (in-progress? year) (conj year))
        days (apply max (map count (vals years)))
        out (cons (cons "year" (map #(str "day" (inc %)) (range days)))
                  (for [[y cells] years]
                    (cons y (cond-> cells
                              (not (progress y)) (into (repeat (- days (count cells)) "0"))))))]
    (spit counts-file (str (str/join "\n" (map #(str/join "," %) out)) "\n"))
    (println "Added" year "with" (count counts) "days to" counts-file)))

(defn total-counts
  "Prints each year's total early votes, most votes first"
  {:org.babashka/cli {:restrict true}}
  [_]
  (let [[_ & rows] (csv/read-csv (slurp counts-file))
        totals (sort-by (comp - second)
                        (map (fn [[y & cells]]
                               [y (reduce + (map parse-long (remove str/blank? cells)))])
                             rows))]
    (doseq [[y total] totals]
      (printf "%s  %,7d\n" y total))
    (flush)))
