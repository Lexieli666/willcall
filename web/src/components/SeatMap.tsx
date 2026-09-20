import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { SeatRow } from './SeatRow'
import type { SeatMapStore } from '../lib/seatMapStore'

export interface SeatMapProps {
  store: SeatMapStore
  /** Bumped by the parent whenever a delta lands, so the map re-reads the rows' revisions. */
  revision: number
  selectedIds: ReadonlySet<string>
  onSelect: (seatId: string) => void
  /** Reports the measured render time once, after the first paint of the full map. */
  onFirstRenderMeasured?: (milliseconds: number) => void
}

/**
 * The seat map, as real DOM.
 *
 * <h2>Why not canvas</h2>
 *
 * A canvas seat map is one element to a screen reader and nothing at all to a keyboard. Real DOM
 * costs render time and buys a purchase path that works without a mouse and without sight, which
 * is the point of the project. The cost then becomes a budget to hold rather than an excuse.
 *
 * <h2>Keyboard model</h2>
 *
 * ARIA grid semantics with a roving tabindex: the grid is a single tab stop and arrow keys move
 * within it. Making every seat a tab stop would mean five thousand presses to get past the map,
 * which is the failure a naive "make each seat focusable" implementation produces.
 *
 * <ul>
 *   <li>Left/Right — previous/next seat in the row, stopping at the ends
 *   <li>Up/Down — same position in the row above/below, clamped to that row's length
 *   <li>Home/End — first/last seat in the row; with Ctrl, first/last in the whole map
 *   <li>PageUp/PageDown — ten rows at a time
 *   <li>Enter/Space — select, handled by the button itself
 * </ul>
 */
export function SeatMap({ store, revision, selectedIds, onSelect, onFirstRenderMeasured }: SeatMapProps) {
  const gridRef = useRef<HTMLDivElement>(null)
  const measured = useRef(false)
  const [focus, setFocus] = useState<{ row: number; seat: number }>({ row: 0, seat: 0 })
  const pendingFocus = useRef<string | null>(null)

  useLayoutEffect(() => {
    if (measured.current || !onFirstRenderMeasured) return
    measured.current = true

    // Measured from the moment the data was ready to the first frame after the DOM is committed.
    // The caller sets the start mark before rendering; taking the end mark inside
    // requestAnimationFrame is what makes this a render-and-paint figure rather than a
    // React-commit figure, which would flatter it.
    requestAnimationFrame(() => {
      try {
        performance.mark('willcall:seatmap:painted')
        performance.measure(
          'willcall:seatmap:render',
          'willcall:seatmap:data-ready',
          'willcall:seatmap:painted',
        )
        const entries = performance.getEntriesByName('willcall:seatmap:render')
        const last = entries[entries.length - 1]
        if (last) onFirstRenderMeasured(last.duration)
      } catch {
        // No start mark set — a unit test rendering the component directly. Nothing to report.
      }
    })
  }, [onFirstRenderMeasured])

  // Move real DOM focus after a keyboard navigation. Changing tabIndex alone does not move
  // focus, and a roving tabindex that never focuses the new cell is a grid that looks
  // keyboard-navigable and is not.
  useEffect(() => {
    const seatId = pendingFocus.current
    if (!seatId) return
    pendingFocus.current = null
    gridRef.current?.querySelector<HTMLButtonElement>(`#seat-${CSS.escape(seatId)}`)?.focus()
  })

  const moveTo = useCallback(
    (rowIndex: number, seatIndex: number) => {
      const row = store.rows[rowIndex]
      if (!row) return
      const clampedSeat = Math.max(0, Math.min(seatIndex, row.seats.length - 1))
      setFocus({ row: rowIndex, seat: clampedSeat })
      const seat = row.seats[clampedSeat]
      if (seat) pendingFocus.current = seat.id
    },
    [store],
  )

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      const { row, seat } = focus
      const currentRow = store.rows[row]
      if (!currentRow) return
      const rowLength = currentRow.seats.length
      const lastRowIndex = store.rows.length - 1

      switch (event.key) {
        case 'ArrowRight':
          event.preventDefault()
          moveTo(row, Math.min(seat + 1, rowLength - 1))
          break
        case 'ArrowLeft':
          event.preventDefault()
          moveTo(row, Math.max(seat - 1, 0))
          break
        case 'ArrowDown':
          event.preventDefault()
          moveTo(Math.min(row + 1, lastRowIndex), seat)
          break
        case 'ArrowUp':
          event.preventDefault()
          moveTo(Math.max(row - 1, 0), seat)
          break
        case 'Home':
          event.preventDefault()
          if (event.ctrlKey) moveTo(0, 0)
          else moveTo(row, 0)
          break
        case 'End':
          event.preventDefault()
          if (event.ctrlKey) moveTo(lastRowIndex, Number.MAX_SAFE_INTEGER)
          else moveTo(row, rowLength - 1)
          break
        case 'PageDown':
          event.preventDefault()
          moveTo(Math.min(row + 10, lastRowIndex), seat)
          break
        case 'PageUp':
          event.preventDefault()
          moveTo(Math.max(row - 10, 0), seat)
          break
        default:
          break
      }
    },
    [focus, moveTo, store],
  )

  let rowIndex = -1

  return (
    /*
      One grid per section, with the section heading outside it.

      ARIA requires a grid's children to be rows or rowgroups, so the first version — a single
      grid containing <section><h3>…</h3><div role="row">…</div></section> — failed
      aria-required-children and aria-required-parent, and the heading inside the grid also
      produced a heading-order violation. axe caught all three.

      The keyboard handler sits on the wrapper rather than on each grid, so arrow keys still
      navigate across section boundaries as one continuous map: the row index the handler works
      in is global, even though the markup is one grid per section.
    */
    <div ref={gridRef} className="wc-seatmap" data-seat-count={store.size} data-revision={revision}>
      {store.sections.map((section) => (
        <section key={section.id} className="wc-seatmap__section">
          <h2 className="wc-seatmap__section-name" id={`section-${section.id}`}>
            {section.name}
          </h2>
          {/*
            The key handler lives on each grid rather than on the wrapper. A bare <div> with a
            keydown handler and no role is exactly what jsx-a11y's no-static-element-interactions
            rule is for, and the rule is right: a keyboard handler on something with no interactive
            role is invisible to assistive technology. Cross-section navigation still works,
            because the handler and the focus state it reads are shared and index rows globally.

            tabIndex={-1} makes the grid programmatically focusable without adding a tab stop, so
            focus can be returned to it after a resync replaces its contents rather than being
            dumped on <body>.
          */}
          <div
            role="grid"
            aria-labelledby={`section-${section.id}`}
            aria-rowcount={section.rows.length}
            tabIndex={-1}
            onKeyDown={onKeyDown}
          >
            {section.rows.map((row) => {
              rowIndex += 1
              const thisRow = rowIndex
              return (
                <SeatRow
                  key={row.id}
                  row={row}
                  revision={row.revision}
                  selectedIds={selectedIds}
                  rovingIndex={thisRow === focus.row ? focus.seat : -1}
                  onSelect={onSelect}
                />
              )
            })}
          </div>
        </section>
      ))}
    </div>
  )
}
