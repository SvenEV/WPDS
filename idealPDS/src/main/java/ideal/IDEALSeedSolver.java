/*******************************************************************************
 * Copyright (c) 2018 Fraunhofer IEM, Paderborn, Germany.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *  
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Johannes Spaeth - initial API and implementation
 *******************************************************************************/
package ideal;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import boomerang.BackwardQuery;
import boomerang.ForwardQuery;
import boomerang.Query;
import boomerang.WeightedBoomerang;
import boomerang.debugger.Debugger;
import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.results.AbstractBoomerangResults;
import boomerang.results.BackwardBoomerangResults;
import boomerang.results.ForwardBoomerangResults;
import boomerang.seedfactory.SeedFactory;
import boomerang.solver.AbstractBoomerangSolver;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.Stmt;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.EmptyStackWitnessListener;
import sync.pds.solver.OneWeightFunctions;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.nodes.GeneratedState;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.SingleNode;
import wpds.impl.ConnectPushListener;
import wpds.impl.StackListener;
import wpds.impl.Transition;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.WPAStateListener;
import wpds.interfaces.WPAUpdateListener;

public class IDEALSeedSolver<W extends Weight> {

	private final IDEALAnalysisDefinition<W> analysisDefinition;
	private final ForwardQuery seed;
	private final IDEALWeightFunctions<W> idealWeightFunctions;
	private final W zero;
	private final W one;
	private final WeightedBoomerang<W> phase1Solver;
	private final WeightedBoomerang<W> phase2Solver;
	private final Stopwatch analysisStopwatch = Stopwatch.createUnstarted();
	private final SeedFactory<W> seedFactory;
	private WeightedBoomerang<W> timedoutSolver;
	private Multimap<Node<Statement, Val>, Statement> affectedStrongUpdateStmt = HashMultimap.create();
	private Set<Node<Statement, Val>> weakUpdates = Sets.newHashSet();
	private final class AddIndirectFlowAtCallSite implements WPAUpdateListener<Statement, INode<Val>, W> {
		private final Statement callSite;
		private final Statement returnSite;
		private final Val returnedFact;

		private AddIndirectFlowAtCallSite(Statement callSite, Statement returnSite, Val returnedFact) {
			this.callSite = callSite;
			this.returnSite = returnSite;
			this.returnedFact = returnedFact;
		}

		@Override
		public void onWeightAdded(Transition<Statement, INode<Val>> t, W w,
				WeightedPAutomaton<Statement, INode<Val>, W> aut) {

			// Commented out as of
			// typestate.tests.FileMustBeClosedTest.simpleAlias()
			if (t.getLabel().equals(returnSite) /*
												 * && !t.getStart().fact().equals( returnedFact.fact())
												 */) {
				idealWeightFunctions.addNonKillFlow(new Node<Statement, Val>(callSite, returnedFact));
				idealWeightFunctions.addIndirectFlow(
						new Node<Statement, Val>(returnSite, returnedFact),
						new Node<Statement, Val>(returnSite, t.getStart().fact()));
			}
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((callSite == null) ? 0 : callSite.hashCode());
			result = prime * result + ((returnSite == null) ? 0 : returnSite.hashCode());
			result = prime * result + ((returnedFact == null) ? 0 : returnedFact.hashCode());
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
			AddIndirectFlowAtCallSite other = (AddIndirectFlowAtCallSite) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (callSite == null) {
				if (other.callSite != null)
					return false;
			} else if (!callSite.equals(other.callSite))
				return false;
			if (returnSite == null) {
				if (other.returnSite != null)
					return false;
			} else if (!returnSite.equals(other.returnSite))
				return false;
			if (returnedFact == null) {
				if (other.returnedFact != null)
					return false;
			} else if (!returnedFact.equals(other.returnedFact))
				return false;
			return true;
		}

		private IDEALSeedSolver getOuterType() {
			return IDEALSeedSolver.this;
		}
		
	}
	private final class IndirectFlowsAtCallSite implements ConnectPushListener<Statement, INode<Val>, W> {


		private final AbstractBoomerangSolver<W> solver;
		private final Statement cs;

		private IndirectFlowsAtCallSite(AbstractBoomerangSolver<W> solver, Statement cs) {
			this.solver = solver;
			this.cs = cs;
		}

		@Override
		public void connect(Statement callSite, Statement returnSite, INode<Val> returnedFact, W w) {
			if(!solver.valueUsedInStatement((Stmt) callSite.getUnit().get(), returnedFact.fact()))
				return;
			if (callSite.equals(cs)) {
				solver.getCallAutomaton().registerListener(new AddIndirectFlowAtCallSite(callSite, returnSite, returnedFact.fact()));
			}
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((cs == null) ? 0 : cs.hashCode());
			result = prime * result + ((solver == null) ? 0 : solver.hashCode());
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
			IndirectFlowsAtCallSite other = (IndirectFlowsAtCallSite) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (cs == null) {
				if (other.cs != null)
					return false;
			} else if (!cs.equals(other.cs))
				return false;
			if (solver == null) {
				if (other.solver != null)
					return false;
			} else if (!solver.equals(other.solver))
				return false;
			return true;
		}

		private IDEALSeedSolver getOuterType() {
			return IDEALSeedSolver.this;
		}
	}

	private final class TriggerBackwardQuery extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {

		private final AbstractBoomerangSolver<W> s;
		private final WeightedBoomerang<W> boomerang;
		private final Node<Statement, Val> curr;

		private TriggerBackwardQuery(INode<Node<Statement, Val>> state, AbstractBoomerangSolver<W> s, WeightedBoomerang<W> boomerang, Node<Statement, Val> curr) {
			super(state);
			this.s = s;
			this.boomerang = boomerang;
			this.curr = curr;
		}

		@Override
		public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
			if (!t.getLabel().equals(Field.empty())) {
				return;
			}
			BackwardQuery query = new BackwardQuery(curr.stmt(), curr.fact());
			BackwardBoomerangResults<W> backwardSolveUnderScope = boomerang.backwardSolveUnderScope(query, seed, curr);
			Map<ForwardQuery, AbstractBoomerangResults<W>.Context> allocationSites = backwardSolveUnderScope
					.getAllocationSites();
			addAffectedPotentialStrongUpdate(curr, curr.stmt());
			idealWeightFunctions.potentialStrongUpdate(curr.stmt());
			s.getCallAutomaton().registerListener(new StackListener<Statement, INode<Val>, W>(s.getCallAutomaton(),
					new SingleNode<Val>(curr.fact()), curr.stmt()) {
				@Override
				public void anyContext(Statement end) {
				}

				@Override
				public void stackElement(Statement callSite) {
					boomerang.checkTimeout();
					for (Statement cs : s.getPredsOf(callSite)) {
						addAffectedPotentialStrongUpdate(curr, cs);
						for (ForwardQuery e : allocationSites.keySet()) {
							AbstractBoomerangSolver<W> solver = boomerang.getSolvers().get(e);
							solver.getCallAutomaton()
									.registerConnectPushListener(new IndirectFlowsAtCallSite(solver, cs));
						}

					}
				}
			});
			for (final Entry<Query, AbstractBoomerangSolver<W>> e : boomerang.getSolvers().entrySet()) {
				if (e.getKey() instanceof ForwardQuery) {
					e.getValue().synchedEmptyStackReachable(curr, new EmptyStackWitnessListener<Statement, Val>() {
						@Override
						public void witnessFound(Node<Statement, Val> targetFact) {
							if (!e.getKey().asNode().equals(seed.asNode())) {
								setWeakUpdate(curr);
							}
						}
					});
				}
			}

			for (ForwardQuery e : allocationSites.keySet()) {
				AbstractBoomerangSolver<W> solver = boomerang.getSolvers().get(e);
				solver.getCallAutomaton().registerListener(new WPAUpdateListener<Statement, INode<Val>, W>() {
					@Override
					public void onWeightAdded(Transition<Statement, INode<Val>> t, W w,
							WeightedPAutomaton<Statement, INode<Val>, W> aut) {

						for (Statement succ : solver.getSuccsOf(curr.stmt())) {

							// Commented out as of typestate.tests.FileMustBeClosedTest.simpleAlias()
							if (t.getLabel().equals(succ) /* && !t.getStart().fact().equals(curr.fact()) */) {
								idealWeightFunctions.addNonKillFlow(curr);
								idealWeightFunctions.addIndirectFlow(new Node<Statement, Val>(succ, curr.fact()),
										new Node<Statement, Val>(succ, t.getStart().fact()));
							}
						}
					}
				});
			}
		}

		@Override
		public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
		}
	}

	public enum Phases {
		ObjectFlow, ValueFlow
	};

	public IDEALSeedSolver(IDEALAnalysisDefinition<W> analysisDefinition, ForwardQuery seed,
			SeedFactory<W> seedFactory) {
		this.analysisDefinition = analysisDefinition;
		this.seed = seed;
		this.seedFactory = seedFactory;
		this.idealWeightFunctions = new IDEALWeightFunctions<W>(analysisDefinition.weightFunctions(),
				analysisDefinition.enableStrongUpdates());
		this.zero = analysisDefinition.weightFunctions().getZero();
		this.one = analysisDefinition.weightFunctions().getOne();
		this.phase1Solver = createSolver(Phases.ObjectFlow);
		this.phase2Solver = createSolver(Phases.ValueFlow);
	}

	public ForwardBoomerangResults<W> run() {
		ForwardBoomerangResults<W> resultPhase1 = runPhase(this.phase1Solver, Phases.ObjectFlow);
		if (resultPhase1.isTimedout()) {
			if (analysisStopwatch.isRunning()) {
				analysisStopwatch.stop();
			}
			timedoutSolver = this.phase1Solver;
			throw new IDEALSeedTimeout(this, this.phase1Solver, resultPhase1);
		}
		ForwardBoomerangResults<W> resultPhase2 = runPhase(this.phase2Solver, Phases.ValueFlow);
		if (resultPhase2.isTimedout()) {
			if (analysisStopwatch.isRunning()) {
				analysisStopwatch.stop();
			}
			timedoutSolver = this.phase2Solver;
			throw new IDEALSeedTimeout(this, this.phase2Solver, resultPhase2);
		}
		return resultPhase2;
	}

	private WeightedBoomerang<W> createSolver(Phases phase) {
		return new WeightedBoomerang<W>(analysisDefinition.boomerangOptions()) {
			@Override
			public BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
				return analysisDefinition.icfg();
			}

			@Override
			public Debugger<W> createDebugger() {
				return analysisDefinition.debugger(IDEALSeedSolver.this);
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, W> getForwardCallWeights(ForwardQuery sourceQuery) {
				if (sourceQuery.equals(seed))
					return idealWeightFunctions;
				return new OneWeightFunctions<Statement, Val, Statement, W>(zero, one);
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, W> getForwardFieldWeights() {
				return new OneWeightFunctions<Statement, Val, Field, W>(zero, one);
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, W> getBackwardFieldWeights() {
				return new OneWeightFunctions<Statement, Val, Field, W>(zero, one);
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, W> getBackwardCallWeights() {
				return new OneWeightFunctions<Statement, Val, Statement, W>(zero, one);
			}

			@Override
			public SeedFactory<W> getSeedFactory() {
				return seedFactory;
			}

			@Override
			public boolean preventForwardCallTransitionAdd(ForwardQuery sourceQuery,
					Transition<Statement, INode<Val>> t, W weight) {
				if (phase.equals(Phases.ValueFlow) && sourceQuery.equals(seed)) {
					if (preventStrongUpdateFlows(t, weight)) {
						return true;
					}
				}
				return super.preventForwardCallTransitionAdd(sourceQuery, t, weight);
			}
		};
	}

	protected boolean preventStrongUpdateFlows(Transition<Statement, INode<Val>> t, W weight) {
		if (idealWeightFunctions.isStrongUpdateStatement(t.getLabel())) {
			if (idealWeightFunctions.isKillFlow(new Node<Statement, Val>(t.getLabel(), t.getStart().fact()))) {
				if ((t.getStart() instanceof GeneratedState)) {
				} else {
					return true;
				}
			}
		}
		return false;
	}

	private ForwardBoomerangResults<W> runPhase(final WeightedBoomerang<W> boomerang, final Phases phase) {
		analysisStopwatch.start();
		idealWeightFunctions.setPhase(phase);
		final WeightedPAutomaton<Statement, INode<Val>, W> callAutomaton = boomerang.getSolvers().getOrCreate(seed)
				.getCallAutomaton();

		if (phase.equals(Phases.ValueFlow)) {
			registerIndirectFlowListener(boomerang.getSolvers().getOrCreate(seed));
		}
		callAutomaton.registerConnectPushListener(new ConnectPushListener<Statement, INode<Val>, W>() {

			@Override
			public void connect(Statement callSite, Statement returnSite, INode<Val> returnedFact, W w) {
				if (!callSite.getMethod().equals(returnedFact.fact().m()))
					return;
				if (!callSite.getMethod().equals(returnSite.getMethod()))
					return;
				if(!boomerang.getSolvers().getOrCreate(seed).valueUsedInStatement((Stmt) callSite.getUnit().get(), returnedFact.fact()))
					return;
				if (!w.equals(one)) {
					idealWeightFunctions.addOtherThanOneWeight(new Node<Statement, Val>(callSite, returnedFact.fact()));
				}
			}
		});

		idealWeightFunctions.registerListener(new NonOneFlowListener() {
			@Override
			public void nonOneFlow(final Node<Statement, Val> curr) {
				if (phase.equals(Phases.ValueFlow)) {
					return;
				}
				AbstractBoomerangSolver<W> s = boomerang.getSolvers().getOrCreate(seed);
				s.getFieldAutomaton().registerListener(
						new TriggerBackwardQuery(new SingleNode<Node<Statement, Val>>(curr), s, boomerang, curr));
			}
		});
		ForwardBoomerangResults<W> res = boomerang.solve((ForwardQuery) seed);
		if (phase.equals(Phases.ValueFlow)) {
			boomerang.debugOutput();
		}
		analysisStopwatch.stop();
		return res;
	}

	protected void addAffectedPotentialStrongUpdate(Node<Statement, Val> curr, Statement stmt) {
		if (affectedStrongUpdateStmt.put(curr, stmt)) {
			idealWeightFunctions.potentialStrongUpdate(stmt);
			if (weakUpdates.contains(curr)) {
				idealWeightFunctions.weakUpdate(stmt);
			}
		}
	}

	private void setWeakUpdate(Node<Statement, Val> curr) {
		if (weakUpdates.add(curr)) {
			for (Statement s : Lists.newArrayList(affectedStrongUpdateStmt.get(curr))) {
				idealWeightFunctions.weakUpdate(s);
			}
		}
	}

	private void registerIndirectFlowListener(AbstractBoomerangSolver<W> solver) {
		WeightedPAutomaton<Statement, INode<Val>, W> callAutomaton = solver.getCallAutomaton();
		callAutomaton.registerListener(new WPAUpdateListener<Statement, INode<Val>, W>() {

			@Override
			public void onWeightAdded(Transition<Statement, INode<Val>> t, W w,
					WeightedPAutomaton<Statement, INode<Val>, W> aut) {
				if (t.getStart() instanceof GeneratedState)
					return;
				Node<Statement, Val> source = new Node<Statement, Val>(t.getLabel(), t.getStart().fact());
				Collection<Node<Statement, Val>> indirectFlows = idealWeightFunctions.getAliasesFor(source);
				for (Node<Statement, Val> indirect : indirectFlows) {
					solver.addNormalCallFlow(source, indirect);
					for (Statement pred : solver.getPredsOf(t.getLabel())) {
						solver.addNormalFieldFlow(new Node<Statement, Val>(pred, indirect.fact()), indirect);
					}

				}
			}
		});
	}

	public WeightedBoomerang<W> getPhase1Solver() {
		return phase1Solver;
	}

	public WeightedBoomerang<W> getPhase2Solver() {
		return phase2Solver;
	}

	public Stopwatch getAnalysisStopwatch() {
		return analysisStopwatch;
	}

	public boolean isTimedOut() {
		return timedoutSolver != null;
	}

	public WeightedBoomerang getTimedoutSolver() {
		return timedoutSolver;
	}

	public Query getSeed() {
		return seed;
	}
}
