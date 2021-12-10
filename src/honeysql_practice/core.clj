(ns honeysql-practice.core
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer [select from where insert-into columns 
              values composite update set delete delete-from join truncate
              union union-all intersect except] :as h])
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

;; Composite types
; 複合型をサポート
(-> (insert-into :comp_table)
    (columns :namd :comp_column)
    (values
     [["small" (composite 1 "inch")]
      ["large" (composite 10 "feet")]])
    (sql/format))
; => ["
;INSERT INTO comp_table
;(name, comp_column)
;VALUES (?, (?, ?)), (?, (?, ?))"
;     "small" 1 "inch" "large" 10 "feet"]

; DSL の場合
(-> {:insert-into [:comp_table]
     :columns [:name :comp_column]
     :values [["small" [:composite 1 "inch"]]
              ["large" [:composite 10 "feet"]]]}
    (sql/format))

;; Updates
(-> (update :films)
    (set {:kind "dramatic"
          :watched [:+ :watched 1]})
    (where [:= :kind "drama"])
    (sql/format))
; 複合更新ステートメント（fromまたはjoinを使用）を作成する場合、
; SETを表示する場所が、データベースごとに構文がわずかに異なることに注意

;; Deletes
(-> (delete-from :films)
    (where [:<> :kind "mussical"])
    (sql/format))
; DSLの場合
(-> {:delete-from :films
     :where [:<> :kind "musical"]}
    (sql/format))

;複数のテーブルから削除がサポートされている場合
(-> (delete [:films :directors])
    (from :films)
    (join :directors [:= :films.director_id :directors.id])
    (where [:<> :kind "musical"])
    (sql/format))
;=> ["
;DELETE films, directors
;FROM films
;INNER JOIN directors ON films.director_id = directors.id
;WHERE kind <> ?"
;    "musical"]

; DSL
(-> {:delete [:films :directors]
     :from [:films]
     :join [:directors [:= :films.director_id :directors.id]]
     :where [:<> :kind "musical"]}
    (sql/format {:pretty true}))

; テーブルからすべてを削除する場合は、truncateを使用できます。
(-> (truncate :films)
    (sql/format) ) 
;=> ["TRUNCATE films"]
; DSL
(-> {:truncate :films}
    (sql/format))

;; Set operations
;：union ：union-all ：intersect :except も利用可能
(sql/format (union (-> (select :*) (from :foo))
                   (-> (select :*) (from :bar))))
;=> ["SELECT * FROM foo UNION SELECT * FROM bar"]
; DSL
(sql/format {:union [{:select :* :from :foo}
                     {:select :* :from :bar}]})

;; Functions
; ％で始まるキーワードは、SQL関数呼び出しとして解釈されます
(-> (select :%count.*) (from :foo) sql/format)
(-> (select :%max.id) (from :foo) sql/format)
;通常の関数呼び出しは[]で示されるが、エイリアスペアも[]で示されるため、%の方が便利
(-> (select [[:count :*]]) (from :foo) sql/format)
(-> {:select [[[:max :id]]], :from [:foo] } sql/format)

;; Bindable parameters
;？で始まるキーワードは、バインド可能なパラメータとして解釈されます
(-> (select :id)
    (from :foo)
    (where[:= :a :?baz])
    (sql/format {:params {:baz "BAZ"}}))
;=> ["SELECT id FROM foo WHERE a = ?" "BAZ"]
(-> {:select [:id] :from [:foo] :where [:= :a :?baz]}
    (sql/format {:params {:baz "BAZ"}}))

;Miscellaneous
; :raw構文を使用すると、SQLフラグメントをHoneySQL式に直接埋め込むことができます。
; :inline構文は、Clojure値をSQL値に変換してから、その文字列を埋め込みます。
; :param構文は、フォーマットする:params引数を介して値が提供される名前付きパラメーターを識別します。
; :lift構文は、DSLの一部としてのClojureデータ構造の解釈を防ぎ、代わりにそのような値をパラメーターに変換します
; :nest構文では、引数をSQL式としてフォーマットした後、追加の括弧のセットが引数の周りにラップされます。
(def call-qualify-map
  (-> (select [[:foo :bar]] [[:raw "@var := foo.bar"]])
      (from :foo)
      (where [:= :a [:param :baz]] [:= :b [:inline 42]])))
;=>{:where [:and [:= :a [:param :baz]] [:= :b [:inline 42]]]
;  :from (:foo)
;  :select [[[:foo :bar]] [[:raw "@var := foo.bar"]]]}
(sql/format call-qualify-map {:params {:baz "BAZ"}})
;=> ["SELECT FOO(bar), @var := foo.bar 
;    FROM foo 
;    WHERE (a = ?) AND (b = 42)" "BAZ"]
(-> (select :*)
    (from :foo)
    (where [:< :expired_at [:raw ["now() - '" 5 " seconds'"]]])
    (sql/format))
;=> ["SELECT * FROM foo WHERE expired_at < now() - '5 seconds'"]
(-> (select :*)
    (from :foo)
    (where [:< :expired_at [:raw ["now() - '" [:lift 5] " seconds'"]]])
    (sql/format))
;=> ["SELECT * FROM foo WHERE expired_at < now() - '? seconds'" 5]
(-> (select :*)
    (from :foo)
    (where [:< :expired_at [:raw ["now() - '" [:param :t] " seconds'"]]])
    (sql/format {:params {:t 5}}))
;=> ["SELECT * FROM foo WHERE expired_at < now() - '? seconds'" 5]
(-> (select :*)
    (from :foo)
    (where [:< :expired_at [:raw ["now() - " [:inline (str 5 " seconds")]]]])
    (sql/format))
;=> ["SELECT * FROM foo WHERE expired_at < now() - '5 seconds'"]

;PostGIS 
;PostgreSQLデータベースで地理空間情報を扱うための拡張
(-> (insert-into :sample)
    (values [{:location [:ST_SetSRID
                         [:ST_MakePoint 0.291 32.621]
                         [:cast 4325 :integer]]}])
    (sql/format {:pretty true}))

;; 
; 識別子をクオートするには、:quoted trueオプションを渡してフォーマットすると、
; 選択した方言に従って引用されます。
; formatで方言をオーバーライドする場合、：dialectオプションを渡すことにより、
; 識別子は自動的に引用符で囲まれます。
; (-> (select :foo.a)
;     (from :foo)
;     (where [:= :foo.a "baz"])
;     (sql/format {:dialect :mysql}))
; => ["SELECT `foo`.`a` FROM `foo` WHERE `foo`.`a` = ?" "baz"]

;Locking
;ANSI / PostgreSQL / SQLServerは、次のようにFOR句を介した選択のロックをサポートします。
; (-> (select :foo.a)
;     (from :foo)
;     (where [:= :foo.a "baz"])
;     (for :update)
;     (sql/format))
; => ["SELECT foo.a FROM foo WHERE foo.a = ? FOR UPDATE" "baz"]

; (sql/format {:select [:*] :from :foo
;              :where [:= :name [:inline "Jones"]]
;              :lock [:in-share-mode]}
;             {:dialect :mysql :quoted false})
; => ["SELECT * FROM foo WHERE name = 'Jones' LOCK IN SHARE MODE"]

;;
;ベクトルの最初の要素として表示されるキーワード（またはシンボル）は、
;演算子または「特別な構文」として宣言されていない限り、ジェネリック関数として扱われます。
;ハッシュマップでキーとして表示されるキーワード（または記号）は、SQL句として扱われ
;組み込みであるか、新しい句として登録されている必要があります。

;データベースが演算子として<=>をサポートしている場合は、
;register-op！を使用してHoneySQLに通知できます。
(-> (select :a) (where [:<=> "food" :a "fool"]) sql/format)
;=>["SELECT a WHERE <=>(?, a, ?)" "food" "fool"]
(sql/register-op! :<=>)
(-> (select :a) (where [:<=> "food" :a "fool"]) sql/format)
;=> ["SELECT a WHERE a <=> ?" "foo"] ?
(sql/register-op! :<=> :variadic true)
(-> (select :a) (where [:<=> "food" :a "fool"]) sql/format)
;=> ["SELECT a WHERE ? <=> a <=> ?" "food" "fool"] ?

;演算子にnil句を無視させたい場合
(sql/register-op! :<=> :ignore-nil true)

;または、データベースがa BETWIXT b AND cのような構文をサポートしている場合は、
;register-fnを使用できます。
(sql/register-fn! :betwixt
                  (fn [op [a b c]]
                    (let [[sql-a & params-a] (sql/format-expr a)
                          [sql-b & params-b] (sql/format-expr b)
                          [sql-c & params-c] (sql/format-expr c)]
                      (-> [(str sql-a " " (sql/sql-kw op) " "
                                sql-b " AND " sql-c)]
                          (c/into params-a)
                          (c/into params-b)
                          (c/into params-c)))))
;; example usage:
(-> (select :a) (where [:betwixt :a 1 10]) sql/format)
;=> ["SELECT a WHERE a BETWIXT ? AND ?" 1 10]

;SQL句を登録することもできます
(sql/register-clause! :foobar
                      (fn [clause x]
                        (let [[sql & params]
                              (if (ident? x)
                                (sql/format-expr x)
                                (sql/format-dsl x))]
                          (c/into [(str (sql/sql-kw clause) " " sql)] params)))
                      :from) ; SELECT ... FOOBAR ... FROM ...
;; example usage:
(sql/format {:select [:a :b] :foobar :baz})
;=> ["SELECT a, b FOOBAR baz"]
(sql/format {:select [:a :b] :foobar {:where [:= :id 1]}})
;=> ["SELECT a, b FOOBAR WHERE id = ?" 1]