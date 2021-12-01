(ns honeysql-practice.core
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer [select from where insert-into columns values] :as h])
  (:gen-class))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))

(def sqlmap {:select [:a :b :c]
             :from [:foo]
             :where [:= :f.a "baz"]})

;; format
(sql/format sqlmap)

(-> '{select (a,b,c) from (foo) where (= f.a "baz")}
    sql/format)

(sql/format sqlmap {:inline true})

(def q-sqlmap {:select [:foo/a :foo/b :foo/c]
               :from [:foo]
               :where [:= :foo/a "baz"]})

(sql/format q-sqlmap)

;;Vanilla SQL clause helpers
(-> (select :a :b :c)
    (from :foo)
    (where [:= :f.a "baz"]))

(= (-> (select :*) (from :foo))
   (-> (from :foo) (select :*)))

(-> sqlmap (select :d))

(-> sqlmap
    (dissoc :select)
    (select :*)
    (where [:> :b 10])
    sql/format)

;"where" will combine multiple clauses together using SQL's "AND":
; a = 1 AND b < 100
(-> (select :*)
    (from :foo)
    (where [:= :a 1] [:< :b 100])
    sql/format)


; b as bar, d as x, foo as quux
(-> (select :a [:b :bar] :c [:d :x])
    (from [:foo :quux])
    (where [:= :quux.a 1] [:< :bar 100])
    sql/format)


;; Inserts
(-> (insert-into :prooperties)
    (columns :name :surname :age)
    (values [["John" "Smith" 34]
             ["Andrew" "Cooper" 12]
             ["Jane" "Daniels" 56]])
    (sql/format {:pretty true}))

(-> {:insert-into [:properties]
     :columns [:name :surname :age]
     :values [["John" "Smith" 34]
              ["Andrew" "Cooper" 12]
              ["Jane" "Daniels" 56]]}
    (sql/format))

(-> (insert-into :properties)
    (values [{:name "John" :surname "Smith" :age 34}
             {:name "Andrew" :surname "Cooper" :age 12}
             {:name "Jane" :surname "Daniels" :age 56}])
    (sql/format))

(-> {:insert-into [:properties]
     :values [{:name "John" :surname "Smith" :age 34}
              {:name "Andrew" :surname "Cooper" :age 12}
              {:name "Jane" :surname "Daniels" :age 56}]}
    (sql/format))

(-> (insert-into :properties)
    (values [{:name "John" :surname "Smith" :age 34}
             {:name "Andrew" :age 12}
             {:name "Jane" :surname "Daniels"}])
    (sql/format {:values-default-columns #{:age}}))
;=> (name, surname, age) VALUES (?, ?, ?), (?, NULL, ?), (?, ?, DEFAULT)
;                                              ~~~~             ~~~~~~~

(let [user-id 12345
      role-name "user"]
  (-> (insert-into :user_profile_to_role)
      (values [{:user-id user-id
                :role-id (-> (select :id)
                             (from :role)
                             (where [:= :name role-name]))}])
      (sql/format))  )

(let [user-id 12345
      role-name "user"]
  (-> {:insert-into [:user_profile_to_role]
       :values [{:user_profile_id 12345
                 :role-id {:select [:id]
                           :from [:role]
                           :where [:= :name "user"]}}]}
      (sql/format)))

(-> (select :*)
    (from :foo)
    (where [:in :foo.a (-> (select :a)
                           (from :bar))])
    (sql/format))