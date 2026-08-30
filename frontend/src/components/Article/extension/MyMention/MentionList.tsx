import React, {useEffect, useImperativeHandle, useRef, useState} from 'react'
import {Button} from "antd";

export default props => {
  const [selectedIndex, setSelectedIndex] = useState(0)
  // 存储每个按钮的 ref
  const itemRefs = useRef<(HTMLElement | null)[]>([])

  const selectItem = (index: number) => {
    const item = props.items[index]
    if (item) {
      props.command({id: item})
    }
  }

  const upHandler = () => {
    setSelectedIndex((selectedIndex + props.items.length - 1) % props.items.length)
  }

  const downHandler = () => {
    setSelectedIndex((selectedIndex + 1) % props.items.length)
  }

  const enterHandler = () => {
    selectItem(selectedIndex)
  }

  useEffect(() => setSelectedIndex(0), [props.items])

  // selectedIndex 变化时，自动滚动到对应项
  useEffect(() => {
    const el = itemRefs.current[selectedIndex]
    if (el) {
      el.scrollIntoView({
        block: 'nearest', // 仅在必要时滚动，不强制居中
      })
    }
  }, [selectedIndex])

  useImperativeHandle(props.ref, () => ({
    onKeyDown: ({event}) => {
      if (event.key === 'ArrowUp') {
        upHandler()
        return true
      }
      if (event.key === 'ArrowDown') {
        downHandler()
        return true
      }
      if (event.key === 'Enter') {
        enterHandler()
        return true
      }
      return false
    },
  }))

  return (
    <div
      className="
        bg-white
        p-[2px]
        border-solid border-2 border-purple-400
        rounded-[5px]
        flex flex-col
        gap-[2px]
        max-h-52
        overflow-y-auto
      "
      style={{
        scrollbarWidth: 'none',      // Firefox
        msOverflowStyle: 'none',     // IE / Edge
      }}
    >
      {props.items.length ? (
        props.items.map((item, index) => (
          <Button
            key={index}
            // 将每个按钮的 DOM 元素存入 itemRefs
            ref={(el) => {
              itemRefs.current[index] = el
            }}
            onClick={() => selectItem(index)}
            className={`
              !px-[2px] !py-[2px]
              !text-purple-600
              !border-none
              !shadow-none
              !rounded-[3px]
              ${index === selectedIndex
              ? '!bg-purple-200'
              : '!bg-purple-100'
            }
              hover:!bg-purple-200
            `}
          >
            {item}
          </Button>
        ))
      ) : (
        <div className="px-[2px] py-[2px] text-slate-400 text-sm">
          No result
        </div>
      )}
    </div>
  )
}
