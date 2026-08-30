import {i18nText} from '@/utils/i18n';
import {computePosition, flip, shift} from '@floating-ui/dom'
import {posToDOMRect, ReactRenderer} from '@tiptap/react'

import MentionList from './MentionList.tsx'

const updatePosition = (editor, element) => {
  const virtualElement = {
    getBoundingClientRect: () => posToDOMRect(editor.view, editor.state.selection.from, editor.state.selection.to),
  }

  computePosition(virtualElement, element, {
    placement: 'bottom-start',
    strategy: 'absolute',
    middleware: [shift(), flip()],
  }).then(({x, y, strategy}) => {
    element.style.width = 'max-content'
    element.style.position = strategy
    element.style.left = `${x}px`
    element.style.top = `${y}px`
  })
}

export default {
  items: ({query}) => {
    // TODO 从后端获取拥有此文章权限的人员列表
    return [
      i18nText("app.article.mymention.suggestion.f359f44e"),
      i18nText("app.article.mymention.suggestion.92a0dd0f"),
      i18nText("app.article.mymention.suggestion.660f3264"),
      i18nText("app.article.mymention.suggestion.711fe082"),
      i18nText("app.article.mymention.suggestion.05a3660a"),
      i18nText("app.article.mymention.suggestion.7acd9bb8"),
      i18nText("app.article.mymention.suggestion.3657b147"),
    ].filter(item => item.toLowerCase().startsWith(query.toLowerCase()))
  },

  render: () => {
    let component

    return {
      onStart: props => {
        component = new ReactRenderer(MentionList, {
          props,
          editor: props.editor,
        })
        if (!props.clientRect) {
          return
        }
        component.element.style.position = 'absolute'
        document.body.appendChild(component.element)
        updatePosition(props.editor, component.element)
      },

      onUpdate(props) {
        component.updateProps(props)
        if (!props.clientRect) {
          return
        }
        updatePosition(props.editor, component.element)
      },

      onKeyDown(props) {
        if (props.event.key === 'Escape') {
          component?.destroy()

          return true
        }
        return component?.ref?.onKeyDown(props)
      },

      onExit() {
        component?.element.remove()
        component?.destroy()
      },
    }
  },
}
