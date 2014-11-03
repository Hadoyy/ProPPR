package edu.cmu.ml.praprolog.prove.wam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.cmu.ml.praprolog.prove.wam.plugins.WamPlugin;
import edu.cmu.ml.praprolog.util.SymbolTable;

/**
 *  # Implementation of opcodes. The convention here is that both
    # registers and heap positions are references relative to the TOP
    # of the register/heap stack, eg a variable index 'a' and
    # 'relativeHeapIndex' should be small negative numbers, -1, -2...
 * @author "William Cohen <wcohen@cs.cmu.edu>"
 * @author "Kathryn Mazaitis <krivard@cs.cmu.edu>"
 *
 */
public class WamInterpreter {
	private static final Logger log = Logger.getLogger(WamInterpreter.class);
	private static final int MAXDEPTH = 0;
	private MutableState state;
	private SymbolTable<String> constantTable;
	private List<Feature> featureStack;
	private Map<Goal,Double> reportedFeatures;
	private WamProgram program;
	private WamPlugin[] plugins;
	public WamInterpreter(WamProgram program, WamPlugin[] plugins) {
		this(new SymbolTable<String>(), program, plugins);
	}
	public WamInterpreter(SymbolTable<String> ct, WamProgram program, WamPlugin[] plugins) {
		this.constantTable = ct;
		this.featureStack = new ArrayList<Feature>();
		this.program = program;
		this.state = new MutableState();
		this.plugins = plugins;
	}
	public void addPlugins(WamPlugin ... plugins) {
		throw new UnsupportedOperationException("Not yet implemented!");
	}

	public WamProgram getProgram() {
		return this.program;
	}
	public ImmutableState saveState() {return this.state.immutableVersion();}
	public void restoreState(State s) {
		this.state = s.mutableVersion();
	}

	public Map<Goal,Double> executeWithoutBranching() { return executeWithoutBranching(-1); }
	public Map<Goal,Double> executeWithoutBranching(boolean computeFeatures) { return executeWithoutBranching(-1, computeFeatures); }
	public Map<Goal,Double> executeWithoutBranching(int startAddress) { return executeWithoutBranching(startAddress, true); }
	/**
	 * Execute instructions until a conditional opcode fails, the
        top-level program return, or a 'callp' or 'freport'
        instruction is reached. Returns a dictionary of features, if
        freport was encountered, or an empty dictionary, otherwise.
        All other status is encoded in the wam's state: completed,
        failed, and/or jumpTo.

        When computeFeatures==False, then all opcodes starting with f
        - the feature-construction opcodes - will be skipped.
	 * @param startAddress
	 * @param computeFeatures
	 */
	public Map<Goal,Double> executeWithoutBranching(int startAddress, boolean computeFeatures) {
		if (startAddress>=0) state.setProgramCounter(startAddress);
		state.setFailed(false);
		state.setCompleted(false);
		this.reportedFeatures = new HashMap<Goal,Double>();
		
		//execute opcodes until we fail, the program completes, or we hit a callp opcode (indicated by setting pc=-1)
		// or we hit a freport opcode (indicated by setting reportedFeatures to a non-empty set)
		while( !state.isFailed() && !state.isCompleted() && this.reportedFeatures.isEmpty() && state.getProgramCounter()>=0) {
			Instruction inst = this.program.getInstruction(state.getProgramCounter());
			execute(inst,computeFeatures);
		}
		return this.reportedFeatures;
	}
	
	public void execute(Instruction inst, boolean computeFeatures) {
		log.debug(inst);
		if (!computeFeatures && inst.opcode.isFeature()) {
			//TODO: pc+= 1 here?
		}
		try {
			switch(inst.opcode) {
			case allocate: allocate(inst.i1); break;
			case callp: callp(inst.s); break;
			case returnp: returnp(); break;
			case pushconst: pushconst(inst.s);break;
			case pushfreevar: pushfreevar(inst.i1);break;
			case pushboundvar: pushboundvar(inst.i1); break;
			case unifyconst: unifyconst(inst.s, inst.i1); break;
			case initfreevar: initfreevar(inst.i1, inst.i2); break;
			case unifyboundvar: unifyboundvar(inst.i1, inst.i2); break;
			case fclear: fclear(); break;
			case fpushstart: fpushstart(inst.s, inst.i1); break;
			case fpushconst: fpushconst(inst.s); break;
			case fpushboundvar: fpushboundvar(inst.i1); break;
			case freport: freport(); break;
			case ffindall: ffindall(inst.i1); break;
			}
		} catch(LogicProgramException e) {
			throw new RuntimeException(e);
		}
		log.debug("at "+this.state.getProgramCounter()+": state "+state);
	}

	private void doFeatureFindallDFS(State state, int depth) throws LogicProgramException {
		if (depth>=MAXDEPTH) throw new IllegalStateException("depth bound "+MAXDEPTH+" exceeded in feature computation");
		if (!state.isCompleted()) {
			for (Outlink o : wamOutlinks(state,false)) {
				doFeatureFindallDFS(o.child, depth+1);
			}
		}
	}
	
	public List<Outlink> wamOutlinks(State s) throws LogicProgramException {
		return wamOutlinks(s,true);
	}
	/** Compute the list of children of an immutableState. 
	 * @throws LogicProgramException */
	public List<Outlink> wamOutlinks(State s, boolean computeFeatures) throws LogicProgramException {
		if (s.isCompleted()) return Collections.emptyList();
		List<Outlink> result = new ArrayList<Outlink>();
		for (WamPlugin plugin : this.plugins) {
			if (plugin.claim(s.getJumpTo())) {
				log.debug("Executing "+s.getJumpTo()+" from "+plugin.about());
				this.restoreState(s);
				for (Outlink o : plugin.outlinks(s, this, computeFeatures)) {
					result.add(o);
				}
				return result;
			}
		}
		if (!this.program.hasLabel(s.getJumpTo())) 
			throw new LogicProgramException("Unknown predicate "+s.getJumpTo());
		for (Integer address : program.getAddresses(s.getJumpTo())) {
			log.debug("Executing "+s.getJumpTo()+" from "+address);
			this.restoreState(s);
			if (computeFeatures) {
				Map<Goal,Double> features = this.executeWithoutBranching(address);
				if (!features.isEmpty() && !this.state.isFailed()) {
					this.executeWithoutBranching();
					if (!this.state.isFailed()) {
						result.add(new Outlink(features,this.saveState()));
					}
				}
			} else {
				this.executeWithoutBranching();
				result.add(new Outlink(null, this.saveState()));
			}
		}
		return result;
	}
	public MutableState getState() {
		return this.state;
	}	
	public void setState(MutableState child) {
		this.state = child;
	}
	public SymbolTable<String> getConstantTable() {
		return constantTable;
	}
	public void setConstantTable(SymbolTable<String> ct) {
		this.constantTable = ct;
	}
	public List<Feature> getFeatureStack() {
		return featureStack;
	}
	public Feature getFeaturePeek() {
		return featureStack.get(featureStack.size()-1);
	}
	public void reportFeature(Goal g) {
		this.reportedFeatures.put(g,1.0);
	}
	
	/********************* op codes ***************************/
	
	public void allocate(int n, String ... names) {
		state.addRegisters(n);
		state.incrementProgramCounter();
	}
	public void callp(String predicate) {
		state.incrementProgramCounter();
		state.getCalls().add(new CallStackFrame(state));
		state.setJumpTo(predicate);
		state.setProgramCounter(-1);
	}
	/** Mark as completed if we're at the top level, and otherwise, pop a
        CallStackFrame and return to that state. */
	public void returnp() {
		if (state.getCalls().isEmpty()) { 
			state.setCompleted(true);
			return;
		}
		CallStackFrame frame = state.getCalls().remove(state.getCalls().size()-1);
		//TODO: debugmode
		state.truncateHeap(frame.getHeapPointer());
		state.truncateRegisters(frame.getRegisterPointer());
//		state.truncateVarNameList(frame.getRegisterPointer());
		state.setProgramCounter(frame.getProgramCounter());
		state.setJumpTo(frame.getJumpTo());
	}
	public void pushconst(String a) {
		int id=getConstantTable().getId(a);
		state.appendHeap(state.createConstantCell(id));
		state.incrementProgramCounter();
	}
	public void pushfreevar(int a) {
		if (a>=0) throw new IllegalArgumentException("a must be <0");
		int i=state.getHeapSize();
		state.appendHeap(state.createVariableCell(i));
		state.setRegister(a,i);
		state.incrementProgramCounter();
	}
	public void pushboundvar(int a) {
		if (a>=0) throw new IllegalArgumentException("a must be <0");
		int valueOfA = state.dereference(state.getRegister(a));
		state.appendHeap(state.createVariableCell(valueOfA));
		state.incrementProgramCounter();
	}
	public void unifyconst(String a, int relativeHeapIndex) throws LogicProgramException {
		if (relativeHeapIndex>=0) throw new IllegalStateException("relative heap index must be <0");
		int i = state.getHeapSize() + relativeHeapIndex;
		int ri = state.dereference(i);
		if (state.hasConstantAt(ri)) {
			if (!getConstantTable().hasId(a)) {
				state.setFailed(true);
			} else {
				int aid = getConstantTable().getId(a);
				state.setFailed(state.getIdOfConstantAt(ri) != aid);
			}
		} else {
			if (!state.hasFreeAt(ri)) throw new LogicProgramException("ri(="+ri+") is neither free nor constant?");
			state.setHeap(ri, state.createConstantCell(getConstantTable().getId(a)));
			state.collapsePointers(i, ri);
		}
		state.incrementProgramCounter();
	}
	public void initfreevar(int a, int relativeHeapIndex) {
		if (a>=0) throw new IllegalArgumentException("a must be <0");
		if (relativeHeapIndex >= 0) throw new IllegalArgumentException("relative heap index must be <0");

		state.setRegister(a, state.getHeapSize() + relativeHeapIndex);
		state.incrementProgramCounter();
	}
	public void unifyboundvar(int a, int relativeHeapIndex) throws LogicProgramException {
		if (a>=0) throw new IllegalArgumentException("a must be <0");
		if (relativeHeapIndex >= 0) throw new IllegalArgumentException("relative heap index must be <0");
		// convert to absolute heap indices
		int i = state.getHeapSize() + relativeHeapIndex;
		int j = state.getRegister(a);
		// follow pointer chains
		int ri = state.dereference(i);
		int rj = state.dereference(j);
		// cases for unification
		if (ri == rj) {
			//fine
		} else if (state.hasConstantAt(ri) && state.hasConstantAt(rj)) {
			state.setFailed(state.getIdOfConstantAt(ri) != state.getIdOfConstantAt(rj));
		} else if (state.hasConstantAt(ri)) {
			if (!state.hasFreeAt(rj)) throw new LogicProgramException("ri constant; rj not free");
			rj = ri;
		} else if (state.hasConstantAt(rj)) {
			if (!state.hasFreeAt(ri)) throw new LogicProgramException("rj constant; ri not free");
			state.setHeap(ri, state.copyConstantCell(rj));
		} else if (rj > ri) {
			if (!state.hasFreeAt(ri) || !state.hasFreeAt(ri)) throw new LogicProgramException("ri,rj not both free");
			state.setHeap(rj, state.createVariableCell(ri)); // bind larger to smaller
			rj = ri;
		} else {
			if ( !(ri > rj) || !state.hasFreeAt(ri) || !state.hasFreeAt(rj)) throw new LogicProgramException("ri,rj not ordered and free");
			state.setHeap(ri, state.createVariableCell(rj)); // bind larger to smaller
			ri = rj;
		}
		state.collapsePointers(i,ri);
		state.collapsePointers(j, rj);
		state.incrementProgramCounter();
		
	}
	public void fclear() {
		this.featureStack.clear();
		this.state.incrementProgramCounter();
	}
	public void fpushstart(String functor, int arity) {
		this.featureStack.add(new Feature(functor,arity));
		this.state.incrementProgramCounter();
	}
	public void fpushconst(String a) {
		getFeaturePeek().append(new ConstantArgument(a));
		this.state.incrementProgramCounter();
	}
	public void fpushboundvar(int a) throws LogicProgramException {
		if (a >= 0) throw new IllegalArgumentException("a must be <0");
		int ra = state.dereference(state.getRegister(a));
		if (!state.hasConstantAt(ra)) throw new LogicProgramException("variable in feature not bound to a constant");
		int cid = state.getIdOfConstantAt(ra);
		getFeaturePeek().append(new ConstantArgument(getConstantTable().getSymbol(cid)));
		state.incrementProgramCounter();
	}
	public void freport() {
		for (Feature f : this.featureStack) {
			Goal g = new Goal(f.functor,f.args);
			reportFeature(g);
		}
		if (this.featureStack.isEmpty()) reportFeature(new Goal("_no_features_"));
		this.state.incrementProgramCounter();
	}
	public void ffindall(int address) throws LogicProgramException {
		// backup the state
		ImmutableState savedState = saveState();
		// clear the call stack and branch to addr
		this.state.getCalls().clear();
		executeWithoutBranching(address);
		// do DFS to find all features
		doFeatureFindallDFS(saveState(),0);
		restoreState(savedState);
		this.state.incrementProgramCounter();
	}
	
	/* ******************** Plugin access ********************* */

	/** Special accessor to the current state: get the value associated
        with the i-th argument, starting at 1, of a arity-k predicate.
        Return null if it is an unbound variable. 
	 * @throws LogicProgramException */
	public String getConstantArg(int k, int i) throws LogicProgramException {
		int rj = this.dereferenceArgument(k,i);
		if (state.hasFreeAt(rj)) return null;
		return heapIndexToConstant(rj);
	}
	private String heapIndexToConstant(int rj) throws LogicProgramException {
		if (!state.hasConstantAt(rj)) throw new LogicProgramException("Expected constant at "+rj);
		int id = state.getIdOfConstantAt(rj);
		return constantTable.getSymbol(id);
	}
	/** Heap index for i-th argument of an arity-k predicate. */
	private int dereferenceArgument(int k, int i) {
		int j = getHeapwiseIndex(k, i);
		return state.dereference(j);
	}
//	public Argument getArg(int k, int i) { return null; }
	private int getHeapwiseIndex(int k, int i) {
		return state.getHeapSize() - k + i - 1;
	}
	/** Special accessor to the current state: set the value associated
        with the i-th argument, starting at 1, of a arity-k predicate. 
	 * @throws LogicProgramException */
	public void setArg(int k, int i, String value) throws LogicProgramException {
		int j = getHeapwiseIndex(k, i);
		int rj = state.dereference(j);
		if (!state.hasFreeAt(rj)) throw new LogicProgramException("var "+rj+" is not free in setArg("+k+","+i+","+value+"):\n"+state.toString());
		log.debug("setArg("+k+","+i+","+value+")");
		int id = constantTable.getId(value);
		state.setHeap(rj, state.createConstantCell(id));
		state.collapsePointers(j, rj);
		log.debug("at _: state "+state);
	}

	/* ************************** trace access ************************* */
	/** Special accessor to the current state: get the value associated
        with the i-th argument, starting at 1, of a arity-k predicate.
        Return None if it is an unbound variable. 
	 * @throws LogicProgramException */
	public Argument getArg(int k, int i) throws LogicProgramException {
		int rj = this.dereferenceArgument(k, i);
		if (this.state.hasFreeAt(rj)) {
			return new VariableArgument(this.heapIndexToVariable(rj));
		} else {
			return new ConstantArgument(this.heapIndexToConstant(rj));
		}
	}
	private int heapIndexToVariable(int rj) throws LogicProgramException {
		if (!this.state.hasFreeAt(rj)) throw new LogicProgramException("var "+rj+" is not free");
		return rj;
	}
	
	/** Convert to a list of pending goals to be proved 
	 * @throws LogicProgramException */
	public List<Goal> pendingGoals(State from) throws LogicProgramException {
		ArrayList<Goal> pending = new ArrayList<Goal>();
		// back up the current state
		State saved = this.saveState();
		this.restoreState(from);
		// simulate excuting the remainder of the program, till completion, but
		// when there is a 'callp', just emit the current goal and return
		while(!this.state.completed) {
			this.executeWithoutBranching(false);
			if (this.state.getJumpTo() != null) {
				pending.add(this.nextPendingGoal());
				this.returnp();
			}
		}
		this.restoreState(saved);
		return pending;
	}
	/** Given that we just did a callp, return a representation of
        the associated goal 
	 * @throws LogicProgramException */
	private Goal nextPendingGoal() throws LogicProgramException {
		State s = this.state;
		String[] parts = state.getJumpTo().split(Compiler.JUMPTO_DELIMITER);
		int arity = Integer.parseInt(parts[1]);
		Argument[] args = new Argument[arity];
		for (int i=1; i<arity+1; i++) {
			args[i-1] = this.getArg(arity, i);
		}
		return new Goal(parts[0],args);
	}
}