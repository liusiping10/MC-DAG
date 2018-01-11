/*******************************************************************************
 * Copyright (c) 2018 Roberto Medina
 * Written by Roberto Medina (rmedina@telecom-paristech.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package fr.tpt.s3.ls_mxc.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import fr.tpt.s3.ls_mxc.model.Actor;
import fr.tpt.s3.ls_mxc.model.ActorSched;
import fr.tpt.s3.ls_mxc.model.DAG;
import fr.tpt.s3.ls_mxc.model.Edge;
import fr.tpt.s3.ls_mxc.util.MathMCDAG;

public class NLevels {
	
	// Set of DAGs to be scheduled
	private Set<DAG> mcDags;
	
	// Architecture + hyperperiod + nb of levels
	private int nbCores;
	private int hPeriod;
	private int levels;
	
	// Scheduling tables
	private String sched[][][];
	
	// Remaining time to be allocated for each node
	// Level, DAG id, Actor id
	private int remainingTime[][][];
	
	// Debugging boolean
	private boolean debug;
	
	// Comparators to order Actors
	private Comparator<ActorSched> loComp;
		
	/**
	 * Constructor
	 * @param dags
	 * @param cores
	 * @param levels
	 * @param debug
	 */
	public NLevels (Set<DAG> dags, int cores, int levels, boolean debug) {
		setMcDags(dags);
		setNbCores(cores);
		setLevels(levels);
		setDebug(debug);
		remainingTime = new int[getLevels()][getMcDags().size()][];
		
		// Init remaining scheduling time tables
		for (DAG d : getMcDags()) {
			for (int i = 0; i < getLevels(); i++) {
				remainingTime[i][d.getId()] = new int[d.getNodes().size()];
			}
		}
		
		setLoComp(new Comparator<ActorSched>() {
			@Override
			public int compare (ActorSched o1, ActorSched o2) {
				if (o1.getUrgencies()[0] - o2.getUrgencies()[0] != 0)
					return o1.getUrgencies()[0] - o2.getUrgencies()[0];
				else
					return o1.getId() - o2.getId();
			}
		});
	}
	
	/**
	 * Initializes the remaining time to be allocated for each node in each level
	 */
	private void initRemainTime () {
		for (int i = 0; i < getLevels(); i++) {
			for (DAG d : getMcDags()) {
				for (Actor a : d.getNodes()) {
					remainingTime[i][d.getId()][a.getId()] = a.getCI(i);
				}	
			}
		}
		if (debug) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] initRemainTime(): Remaining time of actors initialized!");
	}
	
	/**
	 * Inits the scheduling tables and calculates the hyper-period
	 */
	private void initTables () {
		int[] input = new int[getMcDags().size()];
		int i = 0;
		
		for (DAG d : getMcDags()) {
			input[i] = d.getDeadline();
			i++;
		}
		
		sethPeriod(MathMCDAG.lcm(input));
		
		// Init scheduling tables
		for (i = 0; i < getLevels(); i++)
			sched = new String[getLevels()][gethPeriod()][getNbCores()];
		
		for (i = 0; i < getLevels(); i++) {
			for (int j = 0; j < gethPeriod(); j++) {
				for (int k = 0; k < getNbCores(); k++) {
					sched[i][j][k] = "-";
				}
			}
		}
		
		if (debug) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] initTables(): Sched tables initialized!");
	}
	
	/**
	 * Calculates the LFT of an actor in mode l which is a HI mode
	 * @param a
	 * @param l
	 */
	private void calcActorLFTrev (ActorSched a, int l, int dead) {
		int ret = Integer.MAX_VALUE;
		
		if (a.isSourceinL(l)) {
			ret = dead;
		} else {
			int test = Integer.MAX_VALUE;
			
			for (Edge e : a.getRcvEdges()) {
				test = ((ActorSched) e.getSrc()).getLFTs()[l] - e.getSrc().getCI(l);
				if (test < ret)
					ret = test;
			}
		}
		a.setLFTinL(ret, l);
	}
	
	/**
	 * Calculates the LFT of an actor in the LO(west) mode
	 * @param a
	 * @param l
	 */
	private void calcActorLFT (ActorSched a, int l, int dead) {
		int ret = Integer.MAX_VALUE;
		
		if (a.isSinkinL(l)) {
			ret = dead;
		} else {
			int test = Integer.MAX_VALUE;
			
			for (Edge e : a.getSndEdges()) {
				test = ((ActorSched) e.getDest()).getLFTs()[l] - e.getDest().getCI(l);
				if (test < ret)
					ret = test;
			}
		}
		a.setLFTinL(ret, l);
		a.setUrgencyinL(ret, l);
	}
	
	/**
	 * Internal function that tests if all successor of LO nodes in L mode have
	 * been visited.
	 * @param a
	 * @param l
	 * @return
	 */
	private boolean succVisitedinL (ActorSched a, int l) {
		for (Edge e : a.getSndEdges()) {
			if (e.getDest().getCI(l) != 0 && ((ActorSched) e.getDest()).getVisitedL()[l] == false) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Internal function that tests if all predecessors of HI nodes in L mode have
	 * been visited.
	 * @param a
	 * @param l
	 * @return
	 */
	private boolean predVisitedinL (ActorSched a, int l) {
		for (Edge e : a.getRcvEdges()) {
			if (e.getSrc().getCI(l) != 0 && ((ActorSched) e.getSrc()).getVisitedL()[l] == false) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Calculate LFTs on a DAG d
	 * @param d
	 */
	private void calcLFTs (DAG d) {
		
		// Calculate LFTs in HI modes
		for (int i = 1; i < levels; i++) {
			ArrayList<ActorSched> toVisit = new ArrayList<>();
			
			// Calculate sources in i mode
			for (Actor a : d.getNodes()) {
				if (a.isSourceinL(i)) {
					toVisit.add((ActorSched) a);
					((ActorSched) a).getVisitedL()[i] = true;
				}
			}
			
			// Visit all nodes iteratively
			while (!toVisit.isEmpty()) {
				ActorSched a = toVisit.get(0);
				
				calcActorLFTrev(a, i, d.getDeadline());
				for (Edge e : a.getSndEdges()) {
					if (e.getDest().getCI(i) != 0 && !((ActorSched) e.getDest()).getVisitedL()[i]
							&& predVisitedinL((ActorSched) e.getDest(), i)) {
						toVisit.add((ActorSched) e.getDest());
						((ActorSched) e.getDest()).getVisitedL()[i] = true;
					}
				}
				toVisit.remove(0);
			}
		}
		
		// Calculate LFT in LO mode
		ArrayList<ActorSched> toVisit = new ArrayList<>();
		
		for (Actor a : d.getNodes()) {
			if (a.isSinkinL(0)) {
				toVisit.add((ActorSched) a);
				((ActorSched) a).getVisitedL()[0] = true;
			}
		}
		
		while (!toVisit.isEmpty()) {
			ActorSched a = toVisit.get(0);
			
			calcActorLFT(a, 0, d.getDeadline());
			for (Edge e : a.getRcvEdges()) {
				if (!((ActorSched) e.getSrc()).getVisitedL()[0] && succVisitedinL(a, 0)) {
					toVisit.add((ActorSched) e.getSrc());
					((ActorSched) e.getSrc()).getVisitedL()[0] = true;
				}
			}
			toVisit.remove(0);
		}
	}
	
	/**
	 * Checks how many slots have been allocated for a in l mode until
	 * time slot t.
	 * @param a
	 * @param t
	 * @param l
	 * @return
	 */
	private int scheduledUntilTinL (ActorSched a, int t, int l) {
		int ret = 0;
		int start = (int)(t / a.getGraphDead()) * a.getGraphDead();
		
		for (int i = start; i <= t; i++) {
			for (int c = 0; c < nbCores; c++ ) {
				if (sched[l][i][c] !=  null) {
					if (sched[l][i][c].contentEquals(a.getName()))
						ret++;
				}
			}
		}
		
		return ret;
	}
	
	/**
	 * Resets temporary promotions of HI tasks
	 */
	private void resetPromotions() {
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.getCI(1) != 0)
					((ActorSched) a).setPromoted(false);
			}
		}
	}
	
	/**
	 * Calculates the laxity of the ready tasks that are on the list
	 * @param list
	 * @param slot
	 * @param level
	 */
	private void calcLaxity(List<ActorSched> list, int slot, int level) {
		for (ActorSched a : list) {
			int relatSlot = slot % a.getGraphDead();
			int dId = 0;
			
			// Look for the DAG id
			for (DAG d : getMcDags()) {
				for (Actor a2 : d.getNodes()) {
					if (a2.getName().contentEquals(a.getName())) {
						dId = d.getId();
						break;
					}
				}
			}
			
			// The laxity has to be calculated for a HI mode
			if (level >= 1) {
				// It's a HI task that might be promoted
				if (level != getLevels() &&
						(a.getCI(level) - remainingTime[level][dId][a.getId()]) - scheduledUntilTinL(a, slot, level + 1) < 0) {
					a.setPromoted(true);
					if (isDebug()) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] calcLaxity(): Promotion of task "+a.getName()+" at slot @t = "+slot);
					a.setUrgencyinL(0, level);
				} else {
					a.setUrgencyinL(a.getLFTs()[level] - relatSlot - remainingTime[level][dId][a.getId()], level);
				}
			} else { // Laxity in LO mode
				a.setUrgencyinL(a.getLFTs()[level] - relatSlot - remainingTime[level][dId][a.getId()], level);
			}
		}
	}
	
	/**
	 * Functions that verifies if computing the scheduling table is worth it
	 * @param list
	 * @param slot
	 * @param level
	 * @return
	 */
	private boolean isPossible(List<ActorSched> list, int slot, int level) {
		int m = 0;
		ListIterator<ActorSched> lit = list.listIterator();
		
		while (lit.hasNext()) {
			ActorSched a = lit.next();
			
			if (a.getUrgencies()[level] == 0)
				m++;
			else if (a.getUrgencies()[level] < 0)
				return false;
			
			if (m > nbCores)
				return false;
		}
		
		return true;
	}
	
	/**
	 * Builds the scheduling table of level l
	 * @param l
	 * @throws SchedulingException
	 */
	private void buildHITable (int l) throws SchedulingException {
		List<ActorSched> ready = new LinkedList<>();
		List<ActorSched> scheduled = new LinkedList<>();
		
		// Add all sink nodes
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.isSinkinL(l))
					ready.add((ActorSched) a);
			}
		}
		
		calcLaxity(ready, 0, l);
		ready.sort(new Comparator<ActorSched>() {
			@Override
			public int compare(ActorSched o1, ActorSched o2) {
				if (o1.getUrgencies()[l] - o2.getUrgencies()[l] != 0)
					return o1.getUrgencies()[l] - o2.getUrgencies()[l];
				else
					return o2.getId() - o1.getId();
			}
			
		});
		
		// Allocate slot by slot the HI scheduling tables
		ListIterator<ActorSched> lit = ready.listIterator();
		boolean taskFinished = false;
		
		for (int s = hPeriod - 1; s >= 0; s--) {
			if (isDebug()) {
				System.out.print("[DEBUG "+Thread.currentThread().getName()+"] allocHI(): @t = "+s+", tasks activated: ");
				for (ActorSched a : ready)
					System.out.print("L("+a.getName()+") = "+a.getUrgencyHI()+"; ");
				System.out.println("");
			}
			
			// Check if it's worth to continue the allocation
			if (!isPossible(ready, s, l)) {
				SchedulingException se = new SchedulingException("[ERROR "+Thread.currentThread().getName()+"] buildHITable("+l+"): Not enough slots left.")
			}
		}
		
	}
	
	/**
	 * Builds the LO scheduling table
	 * @throws SchedulingException
	 */
	private void buildLOTable () throws SchedulingException {
		
	}
	
	/**
	 * Builds all the scheduling tables for the system
	 */
	public void buildAllTables () {
		initRemainTime();
		initTables();
		
		// Calculate LFTs and urgencies in all DAGs
		for (DAG d : getMcDags()) {
			calcLFTs(d);
			if (isDebug()) printLFTs(d);
		}
		
		// Build tables: more critical tables first
		for (int i = getLevels() - 1; i >= 1; i--) {
			try {
				buildHITable(i);
			} catch (SchedulingException se) {
				System.err.println("[ERROR "+Thread.currentThread().getName()+"] Non schedulable example in mode "+i+".");
			}
		}
		
		try {
			buildLOTable();
		} catch (SchedulingException e) {
			System.err.println("[ERROR "+Thread.currentThread().getName()+"] Non schedulable example in LO mode.");
		}
	}
	
	
	/*
	 * DEBUG functions
	 */
	
	/**
	 * Prints LFTs for all DAGs and all nodes in all the levels
	 * @param d
	 */
	private void printLFTs (DAG d) {
		System.out.println("[DEBUG "+Thread.currentThread().getName()+"] DAG "+d.getId()+" printing LFTs");
		
		for (Actor a : d.getNodes()) {
			System.out.print("[DEBUG "+Thread.currentThread().getName()+"]\t Actor "+a.getName()+", ");
			for (int i = 0; i < getLevels(); i++) {
				if (((ActorSched)a).getLFTs()[i] != Integer.MAX_VALUE)
					System.out.print(((ActorSched)a).getLFTs()[i]);
				System.out.print(" ");
			}
			System.out.println("");
		}
	}
	
	/**
	 * Prints information about the DAGs
	 */
	public void printDAGs () {
		System.out.println("[DEBUG "+Thread.currentThread().getName()+"] N levels: Number of DAGs "+getMcDags().size()+", on "+getLevels()+" levels.");
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				System.out.print("[DEBUG "+Thread.currentThread().getName()+"]\t Actor "+a.getName()+", ");
				for (int i = 0; i < getLevels(); i++)
					System.out.print(a.getCI(i)+" ");
				System.out.println("");
				
				for (Edge e : a.getSndEdges())
					System.out.println("[DEBUG "+Thread.currentThread().getName()+"]\t\t Edge "+e.getSrc().getName()+" -> "+e.getDest().getName());
			}
		}
	}
	
	/*
	 * Getters & Setters
	 */
	
	public Set<DAG> getMcDags() {
		return mcDags;
	}

	public void setMcDags(Set<DAG> mcDags) {
		this.mcDags = mcDags;
	}

	public int getNbCores() {
		return nbCores;
	}

	public void setNbCores(int nbCores) {
		this.nbCores = nbCores;
	}

	public int gethPeriod() {
		return hPeriod;
	}

	public void sethPeriod(int hPeriod) {
		this.hPeriod = hPeriod;
	}

	public int getLevels() {
		return levels;
	}

	public void setLevels(int levels) {
		this.levels = levels;
	}

	public String[][][] getSched() {
		return sched;
	}

	public void setSched(String[][][] sched) {
		this.sched = sched;
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public int[][][] getRemainingTime() {
		return remainingTime;
	}

	public void setRemainingTime(int remainingTime[][][]) {
		this.remainingTime = remainingTime;
	}

	public Comparator<ActorSched> getLoComp() {
		return loComp;
	}

	public void setLoComp(Comparator<ActorSched> loComp) {
		this.loComp = loComp;
	}

}
