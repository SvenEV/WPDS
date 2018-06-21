package boomerang.callgraph;

import soot.Unit;
import soot.Value;
import soot.toolkits.graph.Block;
import soot.toolkits.graph.DirectedGraph;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * An interprocedural control-flow graph.
 *
 * @param <N> Nodes in the CFG, typically {@link Unit} or {@link Block}
 * @param <M> Method representation
 */
public interface ObservableICFG<N,M> {

    /**
     * Returns the method containing a node.
     * @param n The node for which to get the parent method
     */
    M getMethodOf(N n);

    List<N> getPredsOf(N u);

    /**
     * Returns the successor nodes.
     */
    List<N> getSuccsOf(N n);

    /**
     * Registers a listener that will be notified whenever a callee is added
     */
    void addCalleeListener(CalleeListener<N,M> listener);

    /**
     * Adds an edge to the call graph from the caller to the callee, {@link CalleeListener} will
     * be notified
     * @param caller The node that acts as a source of the edge
     * @param callee The node that acts as a target of the edge
     */
    void addCall(N caller, M callee);

    /**
     * Registers a listener that will be notified whenever a caller is added.
     */
    void addCallerListener(CallerListener<N,M> listener);

    /**
     * Returns all call sites within a given method.
     */
    Set<N> getCallsFromWithin(M m);

    /**
     * Returns all start points of a given method. There may be
     * more than one start point in case of a backward analysis.
     */
    Collection<N> getStartPointsOf(M m);

    /**
     * Returns <code>true</code> if the given statement is a call site.
     */
    boolean isCallStmt(N stmt);

    /**
     * Returns <code>true</code> if the given statement leads to a method return
     * (exceptional or not). For backward analyses may also be start statements.
     */
    boolean isExitStmt(N stmt);

    /**
     * Returns true is this is a method's start statement. For backward analyses
     * those may also be return or throws statements.
     */
    boolean isStartPoint(N stmt);

    /**
     * Returns the set of all nodes that are neither call nor start nodes.
     */
    Set<N> allNonCallStartNodes();

    Collection<N> getEndPointsOf(M m);

    Set<N> allNonCallEndNodes();

    List<Value> getParameterRefs(M m);

    /**
     * Checks whether the given statement is reachable from the entry point
     * @param u The statement to check
     * @return True if there is a control flow path from the entry point of the
     * program to the given statement, otherwise false
     */
    boolean isReachable(N u);
}