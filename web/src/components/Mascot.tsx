/**
 * The portal's mascot: Hack4Impact's plush Earth (pale blue, two green continents, rosy cheeks, stubby paws), drawn
 * as SVG so it stays crisp and loads with the page. A few poses for a few friendly, low-stakes places: shrugging when
 * an account isn't linked, napping when there's nothing to do, peeking over an empty list.
 * It holds still: no animation.
 */
export type MascotPose = 'shrug' | 'nap' | 'peek'

const BODY = '#BFE6EF'
const BODY_EDGE = '#94CFDD'
const LAND = '#3DBE5C'
const LAND_LIGHT = '#5ED178'
const CHEEK = '#F3A5B6'
const INK = '#1C1C1C'

export function Mascot({ pose, size = 96, label, className }: { pose: MascotPose; size?: number; label?: string; className?: string }) {
  const peek = pose === 'peek'
  // Peeking shows the top of the globe only, paws over the edge of whatever it sits on.
  const viewBox = peek ? '0 6 140 88' : '0 0 140 132'
  const height = peek ? Math.round((size * 88) / 140) : Math.round((size * 132) / 140)
  return (
    <svg className={`mascot mascot-${pose}${className ? ` ${className}` : ''}`} viewBox={viewBox} width={size} height={height}
      role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true}>
      {label && <title>{label}</title>}
      <defs>
        <clipPath id={`mascot-body-${pose}`}>
          <ellipse cx="70" cy="64" rx="50" ry="48" />
        </clipPath>
      </defs>

      {!peek && <ellipse cx="70" cy="124" rx="40" ry="5" fill="#000" opacity="0.12" />}

      {/* Paws behind the body: shrugging raises both to the sides. */}
      {pose === 'shrug' && (
        <>
          <ellipse cx="17" cy="72" rx="12" ry="10" transform="rotate(-20 17 72)" fill={BODY} stroke={BODY_EDGE} strokeWidth="1.5" />
          <ellipse cx="123" cy="72" rx="12" ry="10" transform="rotate(20 123 72)" fill={BODY} stroke={BODY_EDGE} strokeWidth="1.5" />
        </>
      )}

      <g>
        <ellipse cx="70" cy="64" rx="50" ry="48" fill={BODY} stroke={BODY_EDGE} strokeWidth="1.5" />
        <g clipPath={`url(#mascot-body-${pose})`}>
          <path d="M14 46 C16 26 34 12 52 14 C64 15 70 24 66 34 C63 42 54 42 50 50 C46 58 40 62 30 62 C20 62 13 56 14 46 Z" fill={LAND} />
          <path d="M28 24 C36 18 46 17 52 20" stroke={LAND_LIGHT} strokeWidth="3" strokeLinecap="round" fill="none" opacity="0.7" />
          <path d="M94 22 C106 18 120 28 124 42 C127 56 122 72 112 72 C104 72 104 62 98 58 C90 53 86 44 88 34 C89 28 90 24 94 22 Z" fill={LAND} />
          <path d="M98 26 C106 23 114 28 118 34" stroke={LAND_LIGHT} strokeWidth="3" strokeLinecap="round" fill="none" opacity="0.7" />
        </g>

        {/* Face */}
        {pose === 'nap' ? (
          <>
            <path d="M52 72 q6 5 12 0" stroke={INK} strokeWidth="2.4" strokeLinecap="round" fill="none" />
            <path d="M76 72 q6 5 12 0" stroke={INK} strokeWidth="2.4" strokeLinecap="round" fill="none" />
            <ellipse cx="70" cy="85" rx="2.6" ry="2.1" fill={INK} />
          </>
        ) : (
          <>
            <circle cx="58" cy="72" r="4.6" fill={INK} />
            <circle cx="82" cy="72" r="4.6" fill={INK} />
            <circle cx={peek ? 59.6 : 59.3} cy={peek ? 70.2 : 70.6} r="1.4" fill="#fff" />
            <circle cx={peek ? 83.6 : 83.3} cy={peek ? 70.2 : 70.6} r="1.4" fill="#fff" />
            {pose === 'shrug' ? (
              <>
                <path d="M52 62 q6 -4 11 -1 M77 61 q5 -3 11 1" stroke={INK} strokeWidth="1.8" strokeLinecap="round" fill="none" />
                <path d="M63 85 q3.5 -3 7 0 q3.5 3 7 0" stroke={INK} strokeWidth="2.2" strokeLinecap="round" fill="none" />
              </>
            ) : (
              <path d="M63 81 Q70 88 77 81" stroke={INK} strokeWidth="2.4" strokeLinecap="round" fill="none" />
            )}
          </>
        )}
        <ellipse cx="49" cy="81" rx="5.5" ry="4.3" fill={CHEEK} opacity="0.9" />
        <ellipse cx="91" cy="81" rx="5.5" ry="4.3" fill={CHEEK} opacity="0.9" />
      </g>

      {/* Paws in front */}
      {peek ? (
        <>
          <ellipse cx="44" cy="92" rx="13" ry="9" fill={BODY} stroke={BODY_EDGE} strokeWidth="1.5" />
          <ellipse cx="96" cy="92" rx="13" ry="9" fill={BODY} stroke={BODY_EDGE} strokeWidth="1.5" />
        </>
      ) : (
        <>
          {pose !== 'shrug' && <ellipse cx="40" cy="108" rx="15" ry="11" fill={BODY} stroke={BODY_EDGE} strokeWidth="1.5" />}
          {pose !== 'shrug' && <ellipse cx="100" cy="108" rx="15" ry="11" fill={BODY} stroke={BODY_EDGE} strokeWidth="1.5" />}
        </>
      )}

      {pose === 'shrug' && <text x="116" y="22" fontSize="20" fontWeight="700" fill={BODY_EDGE} fontFamily="system-ui, sans-serif">?</text>}
      {pose === 'nap' && (
        <g fill={BODY_EDGE} fontFamily="system-ui, sans-serif" fontWeight="700">
          <text x="112" y="30" fontSize="14">z</text>
          <text x="122" y="18" fontSize="11">z</text>
          <text x="130" y="9" fontSize="8">z</text>
        </g>
      )}
    </svg>
  )
}
