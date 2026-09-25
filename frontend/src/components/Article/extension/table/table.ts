// Portions adapted from https://github.com/phyohtetarkar/tiptap-block-editor
// Copyright (c) 2025 Phyo Htet Arkar
// SPDX-License-Identifier: MIT
// See LICENSES/tiptap-block-editor-MIT.txt in the repository root.

import {cn} from "@/lib/utils";
import {createColGroup, Table, updateColumns} from "@tiptap/extension-table";
import {type DOMOutputSpec, DOMSerializer} from "@tiptap/pm/model";
import {mergeAttributes} from "@tiptap/react";
import "./table-styles.css";

export type TableBorderStyle = "all" | "horizontal" | "vertical" | "none";
export type TableDensity =
  | "compact"
  | "narrow"
  | "standard"
  | "wide"
  | "spacious";

const applyTableStyleAttributes = (
  root: HTMLElement,
  table: HTMLTableElement,
  attributes: Record<string, unknown>,
) => {
  const striped = attributes.striped === true;
  const headerColored = attributes.headerColored !== false;
  const borderStyle = (attributes.borderStyle || "all") as TableBorderStyle;
  const density = (attributes.density || "standard") as TableDensity;

  root.dataset.tableStriped = String(striped);
  root.dataset.tableHeaderColored = String(headerColored);
  root.dataset.tableBorderStyle = borderStyle;
  root.dataset.tableDensity = density;
  table.dataset.striped = String(striped);
  table.dataset.headerColored = String(headerColored);
  table.dataset.borderStyle = borderStyle;
  table.dataset.density = density;
};

// TODO 当合并表格后，转为 markdown 时，位置不对了，可考虑是否优化
// ── 自定义表格扩展 ───
export const CustomTable = Table.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      striped: {
        default: false,
        parseHTML: (element) => element.dataset.striped === "true",
        renderHTML: (attributes) => ({
          "data-striped": String(attributes.striped === true),
        }),
      },
      headerColored: {
        default: true,
        parseHTML: (element) => element.dataset.headerColored !== "false",
        renderHTML: (attributes) => ({
          "data-header-colored": String(attributes.headerColored !== false),
        }),
      },
      borderStyle: {
        default: "all",
        parseHTML: (element) => element.dataset.borderStyle || "all",
        renderHTML: (attributes) => ({
          "data-border-style": attributes.borderStyle || "all",
        }),
      },
      density: {
        default: "standard",
        parseHTML: (element) => element.dataset.density || "standard",
        renderHTML: (attributes) => ({
          "data-density": attributes.density || "standard",
        }),
      },
    };
  },

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
          style: `width: 100%; min-width: ${tableWidth || tableMinWidth}`,
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

      table.style.width = "100%";
      table.style.minWidth = tableWidth || tableMinWidth;
      applyTableStyleAttributes(dom, table, node.attrs);

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
        update: (updatedNode) => {
          if (updatedNode.type !== node.type) {
            return false;
          }

          updateColumns(
            updatedNode,
            colGroupResult.dom as HTMLTableColElement,
            table,
            this.options.cellMinWidth
          );
          applyTableStyleAttributes(dom, table, updatedNode.attrs);

          const minimumWidth = table.style.width || table.style.minWidth;
          table.style.width = "100%";
          table.style.minWidth = minimumWidth;

          return true;
        },
        ignoreMutation: (_mutation) => true,
      };
    };
  },
});
