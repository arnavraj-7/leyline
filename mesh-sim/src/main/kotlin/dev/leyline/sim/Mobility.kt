package dev.leyline.sim

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.random.Random

/** A position in metres. */
data class Point(val x: Double, val y: Double) {
    fun distanceTo(other: Point): Double = hypot(x - other.x, y - other.y)

    /** The point [metres] along the way to [goal], or [goal] itself if that is closer. */
    fun toward(goal: Point, metres: Double): Point {
        val distance = distanceTo(goal)
        if (distance <= metres) return goal
        val f = metres / distance
        return Point(x + (goal.x - x) * f, y + (goal.y - y) * f)
    }
}

/** Moves one node. Called every tick with the seconds that passed. */
fun interface Mover {
    fun step(from: Point, seconds: Double): Point
}

/** The classic MANET model: walk to a random point at a random speed, pause, repeat. */
class RandomWaypoint(
    private val width: Double,
    private val height: Double,
    private val speeds: ClosedFloatingPointRange<Double>,
    private val maxPauseSeconds: Double,
    private val random: Random,
) : Mover {
    private var goal: Point? = null
    private var speed = 0.0
    private var pause = 0.0

    override fun step(from: Point, seconds: Double): Point {
        if (pause > 0) {
            pause -= seconds
            return from
        }
        val target = goal ?: Point(random.nextDouble() * width, random.nextDouble() * height).also {
            goal = it
            speed = speeds.start + random.nextDouble() * (speeds.endInclusive - speeds.start)
        }
        val next = from.toward(target, speed * seconds)
        if (next == target) {
            goal = null
            pause = random.nextDouble() * maxPauseSeconds
        }
        return next
    }
}

/** Walks [waypoints] in order, forever: a data mule on a fixed route. */
class Patrol(private val waypoints: List<Point>, private val speed: Double) : Mover {
    init {
        require(waypoints.size >= 2 && waypoints.zipWithNext().none { (a, b) -> a == b }) { "route needs distinct stops" }
    }

    private var next = 0

    override fun step(from: Point, seconds: Double): Point {
        var position = from
        var budget = speed * seconds
        while (budget > 0) {
            val goal = waypoints[next]
            val distance = position.distanceTo(goal)
            if (distance > budget) return position.toward(goal, budget)
            position = goal
            budget -= distance
            next = (next + 1) % waypoints.size
        }
        return position
    }
}

/**
 * Positions for a [VirtualNetwork]: two nodes are linked while closer than
 * [range]. Re-evaluated every [tickMillis] of virtual time, roughly how often a
 * phone's scan notices a neighbour appearing or fading away.
 */
class Mobility(
    private val network: VirtualNetwork,
    private val range: Double,
    private val tickMillis: Long = 1_000,
    private val profile: LinkProfile = LinkProfile(),
) {
    private val positions = LinkedHashMap<Int, Point>()
    private val movers = HashMap<Int, Mover>()

    fun place(id: Int, at: Point, mover: Mover? = null) {
        positions[id] = at
        if (mover != null) movers[id] = mover
    }

    fun start(): Job = network.scope.launch {
        while (true) {
            relink()
            delay(tickMillis)
            for ((id, mover) in movers) positions[id] = mover.step(positions.getValue(id), tickMillis / 1000.0)
        }
    }

    /** Brings links in line with current distances. */
    fun relink() {
        val ids = positions.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val (a, b) = ids[i] to ids[j]
            val inRange = positions.getValue(a).distanceTo(positions.getValue(b)) < range
            if (inRange && !network.isLinked(a, b)) network.connect(a, b, profile)
            if (!inRange && network.isLinked(a, b)) network.disconnect(a, b)
        }
    }
}
