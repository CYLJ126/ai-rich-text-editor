import {cn} from "@/lib/utils";
import {createColGroup, Table} from "@tiptap/extension-table";
import {DOMOutputSpec, DOMSerializer} from "@tiptap/pm/model";
import {mergeAttributes} from "@tiptap/react";
import "./table-styles.css";

// TODO 当合并表格后，转为 markdown时，位置不对了，可考虑是否优化
// ── 自定义表格扩展 ───
export const CustomTable = Table.extend({
  renderHTML({ node, HTMLAttributes }) {
    const { colgroup, tableWidth, tableMinWidth } = createColGroup(
        node,
        this.options.cellMinWidth
    );

    const table: DOMOutputSpec = [
      "div",
      {
        class: cn("table-wrapper overflow-y-auto relative"),
      },
      [
        "table",
        mergeAttributes(this.options.HTMLAttributes, HTMLAttributes, {
          style: tableWidth
              ? `width: ${tableWidth}`
              : `min-width: ${tableMinWidth}`,
        }),
        colgroup,
        ["tbody", 0],
      ],
    ];

    return table;
  },

  addNodeView() {
    return ({ node, HTMLAttributes }) => {
      const { colgroup, tableWidth, tableMinWidth } = createColGroup(
          node,
          this.options.cellMinWidth
      );

      const dom = document.createElement("div");
      dom.setAttribute("data-content-type", "table");
      dom.className = cn("mb-4 relative");

      const wrapper = document.createElement("div");
      wrapper.className = cn(
        "table-wrapper",
        "overflow-x-auto overflow-y-hidden",
        "relative"
      );

      const tableContainer = document.createElement("div");
      tableContainer.className = "table-container";

      const table = document.createElement("table");

      // 合并所有 HTMLAttributes
      const mergedAttrs = mergeAttributes(
          this.options.HTMLAttributes,
          HTMLAttributes
      );
      Object.entries(mergedAttrs).forEach(([key, value]) => {
        if (value !== null && value !== undefined) {
          table.setAttribute(key, String(value));
        }
      });

      if (tableWidth) {
        table.style.width = tableWidth;
      } else {
        table.style.minWidth = tableMinWidth;
      }

      const colGroupResult = DOMSerializer.renderSpec(document, colgroup);
      const content = document.createElement("tbody");

      table.append(colGroupResult.dom, content);
      tableContainer.append(table);

      const tableControls = document.createElement("div");
      tableControls.className = "table-controls";

      const tableSelectionContainer = document.createElement("div");
      tableSelectionContainer.className = "table-selection-container";

      wrapper.append(tableContainer, tableSelectionContainer);
      dom.append(wrapper, tableControls);

      return {
        dom,
        contentDOM: content,
        ignoreMutation: (_mutation) => true,
      };
    };
  },
});
