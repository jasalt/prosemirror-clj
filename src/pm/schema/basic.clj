(ns pm.schema.basic
  "The prosemirror-schema-basic 1.2.5 + prosemirror-schema-list 1.5.1 schema
  (`addListNodes(basic.spec.nodes, \"paragraph block*\", \"block\")`) as plain
  data, minus DOM-only keys (parseDOM/toDOM). This is the schema used by the
  differential tests, and a convenient default for consumers."
  (:require [pm.model.schema :as s]))

(def spec
  {:nodes [["doc" {:content "block+"}]
           ["paragraph" {:content "inline*" :group "block"}]
           ["blockquote" {:content "block+" :group "block" :defining true}]
           ["horizontal_rule" {:group "block"}]
           ["heading" {:attrs {:level {:default 1 :validate "number"}}
                       :content "inline*" :group "block" :defining true}]
           ["code_block" {:content "text*" :marks "" :group "block" :code true :defining true}]
           ["text" {:group "inline"}]
           ["image" {:inline true :group "inline"
                     :attrs {:src {:validate "string"}
                             :alt {:default nil :validate "string|null"}
                             :title {:default nil :validate "string|null"}}}]
           ["hard_break" {:inline true :group "inline"}]
           ["ordered_list" {:attrs {:order {:default 1 :validate "number"}}
                            :content "list_item+" :group "block"}]
           ["bullet_list" {:content "list_item+" :group "block"}]
           ["list_item" {:content "paragraph block*" :defining true}]]
   :marks [["link" {:attrs {:href {:validate "string"}
                            :title {:default nil :validate "string|null"}}
                    :inclusive false}]
           ["em" {}]
           ["strong" {}]
           ["code" {:code true}]]})

(def schema (s/schema spec))
