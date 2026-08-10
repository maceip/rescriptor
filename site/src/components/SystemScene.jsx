/**
 * The 3D system view. Lazy loaded: three.js never lands in the entry bundle.
 *
 * Structural nodes are machined aluminum, the mediation tier is glass, and a packet runs the route
 * for the selected mode. The point of the animation is the turnaround: in replay the packet never
 * reaches the service tier, and those nodes visibly go cold.
 */
import { Line, RoundedBox, Text } from '@react-three/drei'
import { Canvas, useFrame } from '@react-three/fiber'
import { memo, useMemo, useRef, useState } from 'react'
import { Color, MathUtils, Vector3 } from 'three'
import plexUrl from '@fontsource/ibm-plex-mono/files/ibm-plex-mono-latin-500-normal.woff?url'
import { EDGES, NODES, NODE_BY_ID, ROUTES, TIERS, position } from '../content/system.js'

// Cards stand up facing the camera so their labels are readable rather than foreshortened.
const NODE_SIZE = [1.34, 0.46, 0.16]
const CORNER = 0.04

function NodeMesh({ node, palette, selected, dimmed, hot, onSelect }) {
  const mesh = useRef()
  const [hovered, setHovered] = useState(false)
  const isMediation = TIERS[node.tier].kind === 'mediation'
  const home = useMemo(() => position(node), [node])

  useFrame((_, delta) => {
    const target = (hovered || selected ? 0.16 : 0) + (hot ? 0.09 : 0)
    const group = mesh.current
    if (!group) return
    group.position.y = MathUtils.damp(group.position.y, home[1] + target, 8, delta)
    const scale = selected ? 1.06 : hovered ? 1.035 : 1
    group.scale.x = MathUtils.damp(group.scale.x, scale, 9, delta)
    group.scale.y = MathUtils.damp(group.scale.y, scale, 9, delta)
  })

  const tone = selected || hot ? palette.accent : isMediation ? palette.glass : palette.metal
  const opacity = dimmed ? 0.22 : isMediation ? 0.72 : 1

  return (
    <group
      ref={mesh}
      position={home}
      onPointerOver={(event) => {
        event.stopPropagation()
        setHovered(true)
        document.body.style.cursor = 'pointer'
      }}
      onPointerOut={() => {
        setHovered(false)
        document.body.style.cursor = ''
      }}
      onClick={(event) => {
        event.stopPropagation()
        onSelect(node.id)
      }}
    >
      <RoundedBox args={NODE_SIZE} radius={CORNER} smoothness={3} steps={1}>
        <meshPhysicalMaterial
          color={tone}
          metalness={isMediation ? 0.1 : 0.82}
          roughness={isMediation ? 0.08 : 0.34}
          clearcoat={1}
          clearcoatRoughness={isMediation ? 0.04 : 0.22}
          transparent
          opacity={opacity}
          emissive={selected || hot ? palette.accent : '#000000'}
          emissiveIntensity={selected ? 0.5 : hot ? 0.32 : 0}
        />
      </RoundedBox>
      <Text
        font={plexUrl}
        fontSize={0.155}
        maxWidth={1.2}
        textAlign="center"
        anchorX="center"
        anchorY="middle"
        position={[0, 0, NODE_SIZE[2] / 2 + 0.004]}
        color={selected || hot ? palette.accentInk : palette.label}
        fillOpacity={dimmed ? 0.32 : 1}
        outlineWidth={0}
      >
        {node.short ?? node.label}
      </Text>
    </group>
  )
}

const Nodes = memo(function Nodes({ palette, selected, dims, hotId, onSelect }) {
  return NODES.map((node) => (
    <NodeMesh
      key={node.id}
      node={node}
      palette={palette}
      selected={selected === node.id}
      dimmed={dims.includes(node.id)}
      hot={hotId === node.id}
      onSelect={onSelect}
    />
  ))
})

const Edges = memo(function Edges({ palette, dims }) {
  return EDGES.map(([from, to]) => {
    const a = position(NODE_BY_ID[from])
    const b = position(NODE_BY_ID[to])
    const cold = dims.includes(from) || dims.includes(to)
    return (
      <Line
        key={`${from}->${to}`}
        points={[a, b]}
        color={palette.edge}
        lineWidth={1}
        transparent
        opacity={cold ? 0.12 : 0.58}
        dashed={false}
      />
    )
  })
})

function TierLabels({ palette }) {
  return TIERS.map((tier) => (
    <Text
      key={tier.label}
      font={plexUrl}
      fontSize={0.135}
      anchorX="right"
      anchorY="middle"
      position={[-3.72, tier.y, 0]}
      color={palette.dim}
    >
      {tier.label.toUpperCase()}
    </Text>
  ))
}

function Packet({ route, palette, onEnter }) {
  const mesh = useRef()
  const lastIndex = useRef(-1)
  const points = useMemo(
    () => route.path.map((id) => new Vector3(...position(NODE_BY_ID[id]))),
    [route],
  )
  const color = useMemo(
    () => new Color(route.tone === 'destructive' ? palette.bad : palette.accent),
    [route.tone, palette.accent, palette.bad],
  )

  useFrame((state) => {
    if (points.length < 2 || !mesh.current) return
    const segments = points.length - 1
    const loop = 5.4
    const phase = (state.clock.elapsedTime % loop) / loop
    // Out and back: the return leg is what shows the response travelling home.
    const travel = phase < 0.56 ? phase / 0.56 : 1 - (phase - 0.56) / 0.44
    const scaled = MathUtils.clamp(travel, 0, 1) * segments
    const index = Math.min(segments - 1, Math.floor(scaled))
    mesh.current.position.lerpVectors(points[index], points[index + 1], scaled - index)

    const nearest = Math.round(scaled)
    if (nearest !== lastIndex.current) {
      lastIndex.current = nearest
      onEnter(route.path[Math.min(segments, nearest)])
    }
  })

  return (
    <mesh ref={mesh}>
      <sphereGeometry args={[0.075, 20, 20]} />
      <meshBasicMaterial color={color} toneMapped={false} />
      <pointLight color={color} intensity={2.4} distance={2.2} />
    </mesh>
  )
}

function Rig({ pointer, children }) {
  const group = useRef()

  useFrame((state, delta) => {
    if (!group.current) return
    const drift = Math.sin(state.clock.elapsedTime * 0.16) * 0.055
    group.current.rotation.y = MathUtils.damp(
      group.current.rotation.y,
      pointer.current.x * 0.34 + drift,
      3.4,
      delta,
    )
    group.current.rotation.x = MathUtils.damp(
      group.current.rotation.x,
      0.06 + pointer.current.y * 0.12,
      3.4,
      delta,
    )
  })

  return <group ref={group}>{children}</group>
}

export default function SystemScene({ mode, selected, onSelect, paused, palette }) {
  const pointer = useRef({ x: 0, y: 0 })
  const [hotId, setHotId] = useState(null)
  const route = ROUTES[mode]

  return (
    <Canvas
      frameloop={paused ? 'never' : 'always'}
      dpr={[1, 1.75]}
      camera={{ position: [0, 0.25, 11.4], fov: 42 }}
      gl={{ antialias: true, alpha: true, powerPreference: 'low-power' }}
      onPointerMove={(event) => {
        const rect = event.currentTarget.getBoundingClientRect()
        pointer.current.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
        pointer.current.y = ((event.clientY - rect.top) / rect.height) * 2 - 1
      }}
      onPointerLeave={() => {
        pointer.current.x = 0
        pointer.current.y = 0
      }}
      onPointerMissed={() => onSelect(null)}
    >
      <ambientLight intensity={1.15} />
      <directionalLight position={[4.5, 7, 6]} intensity={2.1} />
      <directionalLight position={[-6, -2, 3]} intensity={0.5} color={palette.accent} />
      <Rig pointer={pointer}>
        <Edges palette={palette} dims={route.dims} />
        <Nodes
          palette={palette}
          selected={selected}
          dims={route.dims}
          hotId={hotId}
          onSelect={onSelect}
        />
        <TierLabels palette={palette} />
        <Packet route={route} palette={palette} onEnter={setHotId} />
      </Rig>
    </Canvas>
  )
}
