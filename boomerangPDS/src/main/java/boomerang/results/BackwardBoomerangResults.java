package boomerang.results;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import boomerang.BackwardQuery;
import boomerang.ForwardQuery;
import boomerang.Query;
import boomerang.jimple.AllocVal;
import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.solver.AbstractBoomerangSolver;
import boomerang.stats.IBoomerangStats;
import boomerang.util.AccessPath;
import heros.utilities.DefaultValueMap;
import pathexpression.Edge;
import pathexpression.IRegEx;
import pathexpression.LabeledGraph;
import pathexpression.PathExpressionComputer;
import pathexpression.RegEx;
import soot.PointsToSet;
import soot.Type;
import soot.jimple.ClassConstant;
import soot.jimple.NewExpr;
import sync.pds.solver.nodes.AllocNode;
import sync.pds.solver.nodes.GeneratedState;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.SingleNode;
import wpds.impl.Transition;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.Empty;
import wpds.interfaces.WPAStateListener;
import wpds.interfaces.WPAUpdateListener;

public class BackwardBoomerangResults<W extends Weight> implements PointsToSet{

	private final BackwardQuery query;
	private final DefaultValueMap<Query, AbstractBoomerangSolver<W>> queryToSolvers;
	private Map<ForwardQuery,IRegEx<Statement>> allocationSites;
	private final boolean timedout;
	private final IBoomerangStats<W> stats;
	private Stopwatch analysisWatch;

	public BackwardBoomerangResults(BackwardQuery query, boolean timedout, DefaultValueMap<Query, AbstractBoomerangSolver<W>> queryToSolvers, IBoomerangStats<W> stats, Stopwatch analysisWatch) {
		this.query = query;
		this.queryToSolvers = queryToSolvers;
		this.timedout = timedout;
		this.stats = stats;
		this.analysisWatch = analysisWatch;
	}
	public Map<ForwardQuery,IRegEx<Statement>> getAllocationSites(){
		computeAllocations();
		return allocationSites;
	}
	
	public boolean isTimedout() {
		return timedout;
	}
	
	public IBoomerangStats<W> getStats() {
		return stats;
	}
	
	public Stopwatch getAnalysisWatch() {
		return analysisWatch;
	}	

	private void computeAllocations() {
		if(allocationSites != null)
			return;
		final Set<ForwardQuery> results = Sets.newHashSet();
		for (final Entry<Query, AbstractBoomerangSolver<W>> fw : queryToSolvers.entrySet()) {
			if(!(fw.getKey() instanceof ForwardQuery)) {
				continue;
			}
			fw.getValue().getFieldAutomaton().registerListener(new ExtractAllocationSiteStateListener(fw.getValue().getFieldAutomaton().getInitialState(), query, (ForwardQuery) fw.getKey(), results));
			
		}
		allocationSites = Maps.newHashMap();
		for(ForwardQuery q : results) {
			LabeledGraph<INode<Val>, Statement> g = flattenGraph(queryToSolvers.get(q));
			PathExpressionComputer<INode<Val>, Statement> comp = new PathExpressionComputer<>(g);
			IRegEx<Statement> expr = comp.getExpressionBetween(new SingleNode<Val>(query.asNode().fact()),new SingleNode<Val>(q.asNode().fact()));
			IRegEx<Statement> context = allocationSites.get(q);
			if(context != null) {
				context = new RegEx.Union<Statement>(context, expr);
			} else {
				context = expr;
			}
			System.out.println("Call stack: " + expr);
			allocationSites.put(q, context);
		}
	}
	private LabeledGraph<INode<Val>, Statement> flattenGraph(AbstractBoomerangSolver<W> solver) {
		WeightedPAutomaton<Statement, INode<Val>, W> callAutomaton = solver.getCallAutomaton();
		final Set<INode<Val>> nodes = Sets.newHashSet();
		final Set<Edge<INode<Val>,Statement>> edges = Sets.newHashSet();
		callAutomaton.registerListener(new CallStackExtracter(new SingleNode<Val>(query.asNode().fact()),new SingleNode<Val>(query.asNode().fact()),nodes,edges, solver));
		return new LabeledGraph<INode<Val>, Statement>() {

			@Override
			public Set<Edge<INode<Val>, Statement>> getEdges() {
				return edges;
			}

			@Override
			public Set<INode<Val>> getNodes() {
				return nodes;
			}
		};
	}
	
	private class CallStackExtracter extends WPAStateListener<Statement, INode<Val>, W>{

		private Set<INode<Val>> nodes;
		private Set<Edge<INode<Val>, Statement>> edges;
		private AbstractBoomerangSolver<W> solver;
		private INode<Val> source;

		public CallStackExtracter(INode<Val> state, INode<Val> source, Set<INode<Val>> nodes, Set<Edge<INode<Val>, Statement>> edges, AbstractBoomerangSolver<W> solver) {
			super(state);
			this.source = source;
			this.nodes = nodes;
			this.edges = edges;
			this.solver = solver;
		}

		@Override
		public void onOutTransitionAdded(Transition<Statement, INode<Val>> t, W w,
				WeightedPAutomaton<Statement, INode<Val>, W> weightedPAutomaton) {
			if(t.getLabel().getMethod() != null) {
				if(t.getStart() instanceof GeneratedState) {
					Set<Statement> succsOf = solver.getPredsOf(t.getLabel());
					for(Statement s : succsOf) {
						nodes.add(source);
						nodes.add(t.getTarget());
						edges.add(new FEdge(source,s,t.getTarget()));
					}
				} else {
					weightedPAutomaton.registerListener(new CallStackExtracter(t.getTarget(),source, nodes, edges, solver));
					return;
				}
			}
			weightedPAutomaton.registerListener(new CallStackExtracter(t.getTarget(),t.getTarget(), nodes, edges, solver));
		}

		@Override
		public void onInTransitionAdded(Transition<Statement, INode<Val>> t, W w,
				WeightedPAutomaton<Statement, INode<Val>, W> weightedPAutomaton) {
			
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((source == null) ? 0 : source.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			CallStackExtracter other = (CallStackExtracter) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (source == null) {
				if (other.source != null)
					return false;
			} else if (!source.equals(other.source))
				return false;
			return true;
		}

		private BackwardBoomerangResults getOuterType() {
			return BackwardBoomerangResults.this;
		}
		
	}
	

	private static class FEdge implements Edge<INode<Val>,Statement>{

		private INode<Val> start;
		private Statement stmt;
		private INode<Val> target;

		public FEdge(INode<Val> start, Statement stmt, INode<Val> target) {
			this.start = start;
			this.stmt = stmt;
			this.target = target;
		}

		@Override
		public INode<Val> getStart() {
			return start;
		}

		@Override
		public INode<Val> getTarget() {
			return target;
		}

		@Override
		public Statement getLabel() {
			return stmt;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((stmt == null) ? 0 : stmt.hashCode());
			result = prime * result + ((start == null) ? 0 : start.hashCode());
			result = prime * result + ((target == null) ? 0 : target.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			FEdge other = (FEdge) obj;
			if (stmt == null) {
				if (other.stmt != null)
					return false;
			} else if (!stmt.equals(other.stmt))
				return false;
			if (start == null) {
				if (other.start != null)
					return false;
			} else if (!start.equals(other.start))
				return false;
			if (target == null) {
				if (other.target != null)
					return false;
			} else if (!target.equals(other.target))
				return false;
			return true;
		}

		
	}
	
	private class ExtractAllocationSiteStateListener extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {
		
		private ForwardQuery query;
		private Set<ForwardQuery> results;
		private BackwardQuery bwQuery;

		public ExtractAllocationSiteStateListener(INode<Node<Statement, Val>> state,  BackwardQuery bwQuery,ForwardQuery query, Set<ForwardQuery> results) {
			super(state);
			this.bwQuery = bwQuery;
			this.query = query;
			this.results = results;
		}

		@Override
		public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
		}
		
		@Override
		public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
			if(t.getLabel().equals(Field.empty()) && t.getStart().fact().equals(bwQuery.asNode())){
				results.add(query);
			}
		}

		@Override
		public int hashCode() {
			//Otherwise we cannot register this listener twice.
			return System.identityHashCode(this);
		}

		@Override
		public boolean equals(Object obj) {
			//Otherwise we cannot register this listener twice.
			return this == obj;
		}
	}
	
	public Set<AccessPath> getAllAliases() {
		final Set<AccessPath> results = Sets.newHashSet();
		for (final Query fw : getAllocationSites().keySet()) {
			if(fw instanceof BackwardQuery)
				continue;

			final INode<Node<Statement, Val>> allocNode = queryToSolvers.getOrCreate(fw).getFieldAutomaton().getInitialState();			queryToSolvers.getOrCreate(fw).getFieldAutomaton().registerListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {
				@Override
				public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
						WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {

					if(t.getStart().fact().stmt().equals(query.stmt()) && !(t.getStart() instanceof GeneratedState)){
						final Val base = t.getStart().fact().fact();
						if (t.getLabel().equals(Field.empty())) {
							if (t.getTarget().equals(allocNode)) {
								results.add(new AccessPath(base));
							}
						}
						List<Field> fields = Lists.newArrayList();
						if (!(t.getLabel() instanceof Empty)) {
							fields.add(t.getLabel());
						}
						queryToSolvers.getOrCreate(fw).getFieldAutomaton().registerListener(new ExtractAccessPathStateListener(t.getTarget(),allocNode,base, fields, results));
					}
				}
			});
		}
		System.out.println(results);
		return results;
	}
	
	private class ExtractAccessPathStateListener extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {

		private INode<Node<Statement, Val>> allocNode;
		private Collection<Field> fields;
		private Set<AccessPath> results;
		private Val base;

		public ExtractAccessPathStateListener(INode<Node<Statement, Val>> state, INode<Node<Statement, Val>> allocNode,
				Val base, Collection<Field> fields, Set<AccessPath> results) {
			super(state);
			this.allocNode = allocNode;
			this.base = base;
			this.fields = fields;
			this.results = results;
		}

		@Override
		public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
//			if(t.getTarget() instanceof AllocNode && t.getLabel().equals(Field.epsilon()))
//				return;
			if(base.toString().contains("b")) {
				System.out.println(t);
			}
			Collection<Field> copiedFields = (fields instanceof Set ? Sets.newHashSet(fields) : Lists.newArrayList(fields));
			if (!t.getLabel().equals(Field.empty())) {
				if(copiedFields.contains(t.getLabel())){
					copiedFields = Sets.newHashSet(fields);
				}
				if(!(t.getLabel() instanceof Empty))
					copiedFields.add(t.getLabel());
			}
			if (t.getTarget().equals(allocNode)) {
				results.add(new AccessPath(base, copiedFields));
			} else if(t.getTarget() instanceof AllocNode)
				return;
			weightedPAutomaton.registerListener(
							new ExtractAccessPathStateListener(t.getTarget(), allocNode, base, copiedFields, results));
		}

		@Override
		public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((allocNode == null) ? 0 : allocNode.hashCode());
			result = prime * result + ((base == null) ? 0 : base.hashCode());
			result = prime * result + ((fields == null) ? 0 : fields.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			ExtractAccessPathStateListener other = (ExtractAccessPathStateListener) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (allocNode == null) {
				if (other.allocNode != null)
					return false;
			} else if (!allocNode.equals(other.allocNode))
				return false;
			if (base == null) {
				if (other.base != null)
					return false;
			} else if (!base.equals(other.base))
				return false;
			if (fields == null) {
				if (other.fields != null)
					return false;
			} else if (!fields.equals(other.fields))
				return false;
			return true;
		}

		private BackwardBoomerangResults getOuterType() {
			return BackwardBoomerangResults.this;
		}

	}

	
	@Override
	public boolean isEmpty() {
		computeAllocations();
		return allocationSites.isEmpty();
	}
	@Override
	public boolean hasNonEmptyIntersection(PointsToSet other) {
		if(other == this)
			return true;
		if(!(other instanceof BackwardBoomerangResults)) {
			throw new RuntimeException("Expected a points-to set of type " + BackwardBoomerangResults.class.getName());
		}
		BackwardBoomerangResults<W> otherRes = (BackwardBoomerangResults<W>) other;
		Map<ForwardQuery, IRegEx<Statement>> otherAllocs = otherRes.getAllocationSites();
		boolean intersection = false;
		for(Entry<ForwardQuery, IRegEx<Statement>> a : getAllocationSites().entrySet()) {
			for(Entry<ForwardQuery, IRegEx<Statement>> b : otherAllocs.entrySet()) {
				if(a.getKey().equals(b.getKey()) && contextMatch(a.getValue(),b.getValue())) {
					intersection = true;
				}
			}	
		}
		return intersection;
	}
	
	private boolean contextMatch(IRegEx<Statement> value, IRegEx<Statement> value2) {
		return true;
	}
	
	@Override
	public Set<Type> possibleTypes() {
		computeAllocations();
		Set<Type> res = Sets.newHashSet();
		for(ForwardQuery q : allocationSites.keySet()) {
			Val fact = q.asNode().fact();
			if(fact.isNewExpr()) {
				AllocVal alloc = (AllocVal) fact;
				NewExpr expr = (NewExpr) alloc.allocationValue();
				res.add(expr.getType());
			} else {
				res.add(fact.value().getType());
			}
		}
		return res;
	}
	@Override
	public Set<String> possibleStringConstants() {
		throw new RuntimeException("Not implemented!");
	}
	@Override
	public Set<ClassConstant> possibleClassConstants() {
		throw new RuntimeException("Not implemented!");
	}
	
}