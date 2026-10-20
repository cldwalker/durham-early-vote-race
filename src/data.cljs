(ns data)

(defn- lines [text]
  (.filter (.map (.split text #"\r?\n") #(.trim %)) #(not (identical? "" %))))

(defn parse-counts
  "Parses early-vote-counts.csv: a header row of `year,day1,day2,...` followed
  by one row per year of its daily counts. The longest row sets how many days
  a race has, so a year with fewer early voting days has trailing counts of 0
  and a shorter row is a year still in progress. Returns `{:years :days}`,
  where each year's `:data-days` is how many days it has data for and
  `:vote-days` is how many days it had early voting."
  [text]
  (let [rows (.map (.slice (lines text) 1)
                   (fn [l] (.map (.split l ",") #(.trim %))))
        days (.reduce rows #(max %1 (dec (count %2))) 0)]
    {:days days
     :years (.map rows
                  (fn [row]
                    ;; JS truthiness: an empty cell is falsy
                    (let [cells (.slice row 1)
                          data-days (inc (.findLastIndex cells identity))
                          vote-days (inc (.findLastIndex cells #(pos? (js/Number %))))
                          sums (.reduce (.slice cells 0 data-days)
                                        (fn [acc n] (.concat acc (+ (last acc) (js/Number n))))
                                        [0])]
                      {:name (first row) :data-days data-days :vote-days vote-days
                       :complete (== data-days days) :cumulative sums})))}))

(defn parse-phrases
  "Parses voter-phrases.csv: one phrase per line. A line wrapped in double
  quotes is unquoted, a literal `\\n` marks a line break within a phrase and a
  line starting with `#` is a comment."
  [text]
  (.map (.filter (lines text) #(not (.startsWith % "#")))
        (fn [l]
          (-> (if (.test #"^\".*\"$" l) (.replaceAll (.slice l 1 -1) "\"\"" "\"") l)
              (.replaceAll "\\n" "\n")))))
