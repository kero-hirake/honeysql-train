(ns honeysql-practice.core
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer [select from where insert-into columns values] :as h])
  (:gen-class))

; SQLクエリを能わすマップで構築
; カラム名は、キーワードかシンボルで指定(文字列は不可)
(def sqlmap {:select [:a :b :c]
             :from [:foo]
             :where [:= :f.a "baz"]})

;; format
; マップを、next.jdbc(clojure.java.jdbc)五感の、パラメータ化されたSQLに変換
(sql/format sqlmap)
;=> ["SELECT a, b, c FROM foo WHERE f.a = ?" "baz"]

; シンボルからSQL
(-> '{select (a,b,c) from (foo) where (= f.a "baz")}
    sql/format)
;=> ["SELECT a, b, c FROM foo WHERE f.a = ?" "baz"]

; パラメータが不要な場合、:inline true オプションを渡す
(sql/format sqlmap {:inline true})
; => ["SELECT a, b, c FROM foo WHERE f.a = 'baz'"]

; 名前空間で修飾されたキーワードは、テーブル修飾された列として扱われる
(def q-sqlmap {:select [:foo/a :foo/b :foo/c]
               :from [:foo]
               :where [:= :foo/a "baz"]})
(sql/format q-sqlmap)
;=> ["SELECT foo.a, foo.b, foo.c FROM foo WHERE foo.a = ?" "baz"]


;;Vanilla SQL clause helpers
;honey.sql.helpers名前空間に、すべてのSQL句に対応する関数がある
(-> (select :a :b :c)
    (from :foo)
    (where [:= :f.a "baz"]))
;=> {:select [:a :b :c] :from [:foo] :where [:= :f.a "baz"]}

; 順序は関係ない
(= (-> (select :*) (from :foo))
   (-> (from :foo) (select :*)))
;+> treu

;繰り返される句は、自然な評価順序で既存の句にマージされる
(-> sqlmap (select :d))
;=> => {:from [:foo], :where [:= :f.a "baz"], :select [:a :b :c :d]}
;                                                      ~~~~~~~~~~~~

;句を置き換える場合は、既存の句を破棄してから、追加
(-> sqlmap
    (dissoc :select)
    (select :*)
    (where [:> :b 10])
    sql/format)

;where句 に複数の条件を渡すと "AND""で結合される
(-> (select :*)
    (from :foo)
    (where [:= :a 1] [:< :b 100])
    sql/format)
;=> ["SELECT * FROM foo WHERE (a = ?) AND (b < ?)" 1 100]

; 列名とテーブル名は、元の名前とエイリアスをvectorペアにしてエイリアス化できる。
(-> (select :a [:b :bar] :c [:d :x])
    (from [:foo :quux])
    (where [:= :quux.a 1] [:< :bar 100])
    sql/format)
; => ["SELECT a, b AS bar, c, d AS x FROM foo AS quux WHERE (quux.a = ?) AND (bar < ?)" 1 100]
; シンボルを使う時と、ヘルパーを使う時の、[]の違いに注意
; シンボル {:select [:a :b] => select a, b
; ヘルパー (select [:a :b]) => select a as b


;; Inserts
; insert は 2つのパターンをサポート
; パターン1
; 挿入する列を指定してから、各列の値のコレクションである行のコレクションを指定。
(-> (insert-into :prooperties)
    (columns :name :surname :age)
    (values [["John" "Smith" 34]
             ["Andrew" "Cooper" 12]
             ["Jane" "Daniels" 56]])
    (sql/format {:pretty true}))
;=> ["
; INSERT INTO properties
; (name, surname, age)
; VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)"
;   "Jon" "Smith" 34, "Andrew" "Cooper" 12, "Jane" "Daniels" 56]

; DSLの場合
(-> {:insert-into [:properties]
     :columns [:name :surname :age]
     :values [["John" "Smith" 34]
              ["Andrew" "Cooper" 12]
              ["Jane" "Daniels" 56]]}
    (sql/format))
; 行の長さが等しくない場合は、一貫性を保つためにNULL値で埋められる。

; パターン2
; 値をマップとして指定
(-> (insert-into :properties)
    (values [{:name "John" :surname "Smith" :age 34}
             {:name "Andrew" :surname "Cooper" :age 12}
             {:name "Jane" :surname "Daniels" :age 56}])
    (sql/format))
;=> ["
; INSERT INTO properties
; (name, surname, age) VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)"
;    "John" "Smith" 34
;    "Andrew" "Cooper"  12
;    "Jane" "Daniels" 56]

; DSLの場合
(-> {:insert-into [:properties]
     :values [{:name "John" :surname "Smith" :age 34}
              {:name "Andrew" :surname "Cooper" :age 12}
              {:name "Jane" :surname "Daniels" :age 56}]}
    (sql/format))

;指定のないカラムは、NULL値になる。
;ただし、 :values-default-columnsオプションで、NULLの代わりにDEFAULT値をセットできる
(-> (insert-into :properties)
    (values [{:name "John" :surname "Smith" :age 34}
             {:name "Andrew" :age 12}
             {:name "Jane" :surname "Daniels"}])
    (sql/format {:values-default-columns #{:age}}))
;=> (name, surname, age) VALUES (?, ?, ?), (?, NULL, ?), (?, ?, DEFAULT)
;                                              ~~~~             ~~~~~~~

;; Nested subqueries
; 列の値はリテラルである必要はなく、ネストされたクエリにすることができる
(let [user-id 12345
      role-name "user"]
  (-> (insert-into :user_profile_to_role)
      (values [{:user-id user-id
                :role-id (-> (select :id)
                             (from :role)
                             (where [:= :name role-name]))}])
      (sql/format))  )
;=> ["
; INSERT INTO user_profile_to_role
; (user_profile_id, role_id) VALUES (?, (SELECT id FROM role WHERE name = ?))"
;     12345
;     "user"]

;DSLの場合
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
;=> ["SELECT * FROM foo WHERE foo.a IN (SELECT a FROM bar)"]
    
; DSLの場合
(-> {:select [:*]
     :from [:foo]
     :where [:in :foo.a {:select [:a], :from [:bar]}]}
    (sql/format))

