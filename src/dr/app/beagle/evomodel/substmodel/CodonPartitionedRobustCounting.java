/*
 * CodonPartitionedRobustCounting.java
 *
 * Copyright (C) 2002-2012 Alexei Drummond, Andrew Rambaut & Marc A. Suchard
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package dr.app.beagle.evomodel.substmodel;

import dr.app.beagle.evomodel.sitemodel.SiteRateModel;
import dr.app.beagle.evomodel.treelikelihood.AncestralStateBeagleTreeLikelihood;
import dr.app.beagle.evomodel.utilities.TreeTraitLogger;
import dr.evolution.datatype.Codons;
import dr.evolution.tree.NodeRef;
import dr.evolution.tree.Tree;
import dr.evolution.tree.TreeTrait;
import dr.evolution.tree.TreeTraitProvider;
import dr.evomodel.branchratemodel.BranchRateModel;
import dr.evomodel.tree.TreeModel;
import dr.inference.loggers.LogColumn;
import dr.inference.loggers.Loggable;
import dr.inference.markovjumps.StateHistory;
import dr.inference.model.AbstractModel;
import dr.inference.model.Model;
import dr.inference.model.Parameter;
import dr.inference.model.Variable;
import dr.math.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Marc A. Suchard
 * @author Vladimir Minin
 *         <p/>
 *         A class for implementing robust counting for synonymous and nonsynonymous changes in BEAST using BEAGLE
 *         This work is supported by NSF grant 0856099
 *         <p/>
 *         O'Brien JD, Minin VN and Suchard MA (2009) Learning to count: robust estimates for labeled distances between
 *         molecular sequences. Molecular Biology and Evolution, 26, 801-814
 */

public class CodonPartitionedRobustCounting extends AbstractModel implements TreeTraitProvider, Loggable {

    private static final boolean DEBUG = false;

    public static final String UNCONDITIONED_PREFIX = "u_";
    public static final String SITE_SPECIFIC_PREFIX = "c_";
    public static final String TOTAL_PREFIX = "total_";
    public static final String BASE_TRAIT_PREFIX = "base_";
    public static final String COMPLETE_HISTORY_PREFIX = "all_";
    public static final String UNCONDITIONED_PER_BRANCH_PREFIX = "b_u_";

//    public CodonPartitionedRobustCounting(String name, TreeModel tree,
//                                          AncestralStateBeagleTreeLikelihood[] partition,
//                                          Codons codons,
//                                          CodonLabeling codonLabeling,
//                                          boolean useUniformization) {
//        this(name, tree, partition, codons, codonLabeling, useUniformization,
//                StratifiedTraitOutputFormat.SUM_OVER_SITES, StratifiedTraitOutputFormat.SUM_OVER_SITES);
//
//    }

    public CodonPartitionedRobustCounting(String name, TreeModel tree,
                                          AncestralStateBeagleTreeLikelihood[] partition,
                                          Codons codons,
                                          CodonLabeling codonLabeling,
                                          boolean useUniformization,
                                          boolean includeExternalBranches,
                                          boolean includeInternalBranches,
                                          boolean doUnconditionalPerBranch,
                                          boolean saveCompleteHistory,
                                          StratifiedTraitOutputFormat branchFormat,
                                          StratifiedTraitOutputFormat logFormat) {
        super(name);
        this.tree = tree;
        addModel(tree);

        if (partition.length != 3) {
            throw new RuntimeException("CodonPartition models require 3 partitions");
        }

        this.partition = partition;
        this.codonLabeling = codonLabeling;
        branchRateModel = partition[0].getBranchRateModel();
        addModel(branchRateModel);

        List<SubstitutionModel> substModelsList = new ArrayList<SubstitutionModel>(3);
        List<SiteRateModel> siteRateModelsList = new ArrayList<SiteRateModel>(3);

        numCodons = partition[0].getPatternWeights().length;

        for (int i = 0; i < 3; i++) {
            substModelsList.add(partition[i].getBranchModel().getRootSubstitutionModel());
            siteRateModelsList.add(partition[i].getSiteRateModel());
            if (partition[i].getPatternWeights().length != numCodons) {
                throw new RuntimeException("All sequence lengths must be equal in CodonPartitionedRobustCounting");
            }
        }

        this.saveCompleteHistory = saveCompleteHistory;

        productChainModel =
                new ProductChainSubstitutionModel("codonLabeling", substModelsList, siteRateModelsList);
        addModel(productChainModel);

        this.useUniformization = useUniformization;
        if (useUniformization) {
            markovJumps = new UniformizedSubstitutionModel(productChainModel);
            ((UniformizedSubstitutionModel) markovJumps).setSaveCompleteHistory(saveCompleteHistory);
        } else {
            markovJumps = new MarkovJumpsSubstitutionModel(productChainModel);
        }
        double[] synRegMatrix = CodonLabeling.getRegisterMatrix(codonLabeling, codons, true);
        markovJumps.setRegistration(synRegMatrix);

        condMeanMatrix = new double[64 * 64];

        this.branchFormat = branchFormat;
        this.logFormat = logFormat;

        computedCounts = new double[tree.getNodeCount()][]; // TODO Temporary until there exists a helper class

        this.includeExternalBranches = includeExternalBranches;
        this.includeInternalBranches = includeInternalBranches;
        this.doUnconditionedPerBranch = doUnconditionalPerBranch;

        setupTraits();
    }

    public double[] getUnconditionalCountsForBranch(NodeRef child) {
        if (!unconditionsPerBranchKnown) {
            computeAllUnconditionalCountsPerBranch();
            unconditionsPerBranchKnown = true;
        }
        return unconditionedCountsPerBranch[child.getNumber()];
    }

    public double[] getExpectedCountsForBranch(NodeRef child) { // TODO This function will implement TraitProvider
        if (!countsKnown) {
            computeAllExpectedCounts();
        }
        return computedCounts[child.getNumber()];
    }

    private void computeAllExpectedCounts() {
        for (int i = 0; i < tree.getNodeCount(); i++) {
            NodeRef child = tree.getNode(i);
            if (!tree.isRoot(child)) {
                computedCounts[child.getNumber()] = computeExpectedCountsForBranch(child);
            }
        }
        countsKnown = true;
    }

    private double[] computeExpectedCountsForBranch(NodeRef child) {

        // Get child node reconstructed sequence
        final int[] childSeq0 = partition[0].getStatesForNode(tree, child);
        final int[] childSeq1 = partition[1].getStatesForNode(tree, child);
        final int[] childSeq2 = partition[2].getStatesForNode(tree, child);

        // Get parent node reconstructed sequence
        final NodeRef parent = tree.getParent(child);
        final int[] parentSeq0 = partition[0].getStatesForNode(tree, parent);
        final int[] parentSeq1 = partition[1].getStatesForNode(tree, parent);
        final int[] parentSeq2 = partition[2].getStatesForNode(tree, parent);

        double branchRateTime = branchRateModel.getBranchRate(tree, child) * tree.getBranchLength(child);

        double[] count = new double[numCodons];

        if (!useUniformization) {
            markovJumps.computeCondStatMarkovJumps(branchRateTime, condMeanMatrix);
        } else {
            // Fill condMeanMatrix with transition probabilities
            markovJumps.getSubstitutionModel().getTransitionProbabilities(branchRateTime, condMeanMatrix);
        }

        for (int i = 0; i < numCodons; i++) {

            // Construct this child and parent codon

            final int childState = getCanonicalState(childSeq0[i], childSeq1[i], childSeq2[i]);
            final int parentState = getCanonicalState(parentSeq0[i], parentSeq1[i], parentSeq2[i]);

//            final int vChildState = getVladimirState(childSeq0[i], childSeq1[i], childSeq2[i]);
//            final int vParentState = getVladimirState(parentSeq0[i], parentSeq1[i], parentSeq2[i]);

            final double codonCount;

            if (!useUniformization) {
                codonCount = condMeanMatrix[parentState * 64 + childState];
            } else {
                codonCount = ((UniformizedSubstitutionModel) markovJumps).computeCondStatMarkovJumps(
                        parentState,
                        childState,
                        branchRateTime,
                        condMeanMatrix[parentState * 64 + childState]
                );
            }


            if (useUniformization && saveCompleteHistory) {
                UniformizedSubstitutionModel usModel = (UniformizedSubstitutionModel) markovJumps;

                StateHistory history = usModel.getStateHistory();

                // Only report syn or nonsyn changes
                double[] register = usModel.getRegistration();
                history = history.filterChanges(register);

                int historyCount = history.getNumberOfJumps();
                if (historyCount > 0) {
                    double parentTime = tree.getNodeHeight(tree.getParent(child));
                    double childTime = tree.getNodeHeight(child);
                    history.rescaleTimesOfEvents(parentTime, childTime);

                    int n = history.getNumberOfJumps();
                    // MAS may have broken the next line
                    String hstring = history.toStringChanges(i + 1, usModel.dataType);
                    if (DEBUG) {
                        System.err.println("site " + (i + 1) + " : "
                                + history.getNumberOfJumps()
                                + " : "
                                + history.toStringChanges(i + 1, usModel.dataType)
                                + " " + codonLabeling.getText());
                    }

                    if (completeHistoryPerNode == null) {
                        completeHistoryPerNode = new String[tree.getNodeCount()][numCodons];
                    }
//                    completeHistoryPerNode[child.getNumber()][i] = usModel.getCompleteHistory(parentTime, childTime); // TODO Should reformat
                    completeHistoryPerNode[child.getNumber()][i] = hstring;
                }
            }

            count[i] = codonCount;
        }

        return count;
    }

    private void setupTraits() {

        TreeTrait baseTrait = new TreeTrait.DA() {

            public String getTraitName() {
                return BASE_TRAIT_PREFIX + codonLabeling.getText();
            }

            public Intent getIntent() {
                return Intent.BRANCH;
            }

            public double[] getTrait(Tree tree, NodeRef node) {
                return getExpectedCountsForBranch(node);
            }

            public boolean getLoggable() {
                return false;
            }
        };

        if (saveCompleteHistory) {
            TreeTrait stringTrait = new TreeTrait.SA() {

                public String getTraitName() {
                    return COMPLETE_HISTORY_PREFIX + codonLabeling.getText();
                }

                public Intent getIntent() {
                    return Intent.BRANCH;
                }

                public String[] getTrait(Tree tree, NodeRef node) {
                    getExpectedCountsForBranch(node); // Lazy simulation of complete histories
                    List<String> events = new ArrayList<String>();
                    for (int i = 0; i < numCodons; i++) {
                        if (completeHistoryPerNode[node.getNumber()][i] != null) {
                            events.add(completeHistoryPerNode[node.getNumber()][i]);
                        }
                    }
                    String[] array = new String[events.size()];
                    events.toArray(array);
                    return array;
                }

                public boolean getLoggable() {
                    return true;
                }
            };
            treeTraits.addTrait(stringTrait);
        }

        TreeTrait unconditionedSum;
        if (!TRIAL) {
            unconditionedSum = new TreeTrait.D() {
                public String getTraitName() {
                    return UNCONDITIONED_PREFIX + codonLabeling.getText();
                }

                public Intent getIntent() {
                    return Intent.WHOLE_TREE;
                }

                public Double getTrait(Tree tree, NodeRef node) {
                    return getUnconditionedTraitValue();
                }

                public boolean getLoggable() {
                    return false;
                }
            };
        } else {
            unconditionedSum = new TreeTrait.DA() {
                public String getTraitName() {
                    return UNCONDITIONED_PREFIX + codonLabeling.getText();
                }

                public Intent getIntent() {
                    return Intent.WHOLE_TREE;
                }

                public double[] getTrait(Tree tree, NodeRef node) {
                    return getUnconditionedTraitValues();
                }

                public boolean getLoggable() {
                    return false;
                }
            };
        }

        TreeTrait sumOverTreeTrait = new TreeTrait.SumOverTreeDA(
                SITE_SPECIFIC_PREFIX + codonLabeling.getText(),
                baseTrait,
                includeExternalBranches,
                includeInternalBranches) {
            @Override
            public boolean getLoggable() {
                return false;
            }
        };

        // This should be the default output in tree logs
        TreeTrait sumOverSitesTrait = new TreeTrait.SumAcrossArrayD(
                codonLabeling.getText(),
                baseTrait) {
            @Override
            public boolean getLoggable() {
                return true;
            }
        };

        // This should be the default output in columns logs
        TreeTrait sumOverSitesAndTreeTrait = new TreeTrait.SumOverTreeD(
                TOTAL_PREFIX + codonLabeling.getText(),
                sumOverSitesTrait,
                includeExternalBranches,
                includeInternalBranches) {
            @Override
            public boolean getLoggable() {
                return true;
            }
        };


        treeTraitLogger = new TreeTraitLogger(
                tree,
                new TreeTrait[]{sumOverSitesAndTreeTrait}
        );

        treeTraits.addTrait(baseTrait);
        treeTraits.addTrait(unconditionedSum);
        treeTraits.addTrait(sumOverSitesTrait);
        treeTraits.addTrait(sumOverTreeTrait);
        treeTraits.addTrait(sumOverSitesAndTreeTrait);

        if (doUnconditionedPerBranch) {
            TreeTrait unconditionedBase = new TreeTrait.DA() {

                public String getTraitName() {
                    return UNCONDITIONED_PER_BRANCH_PREFIX + codonLabeling.getText();
                }

                public Intent getIntent() {
                    return Intent.BRANCH;
                }

                public double[] getTrait(Tree tree, NodeRef node) {
                    return getUnconditionalCountsForBranch(node);
                }

                public boolean getLoggable() {
                    return false;   // TODO Should be switched to true to log unconditioned values per branch
                }
            };

            TreeTrait sumUnconditionedOverSitesTrait = new TreeTrait.SumAcrossArrayD(
                    UNCONDITIONED_PER_BRANCH_PREFIX + codonLabeling.getText(),
                    unconditionedBase) {
                @Override
                public boolean getLoggable() {
                    return true;
                }
            };

            treeTraits.addTrait(unconditionedBase);
            treeTraits.addTrait(sumUnconditionedOverSitesTrait);
        }
    }

    public TreeTrait[] getTreeTraits() {
        return treeTraits.getTreeTraits();
    }

    public TreeTrait getTreeTrait(String key) {
        return treeTraits.getTreeTrait(key);
    }

    private int getCanonicalState(int i, int j, int k) {
        return i * 16 + j * 4 + k;
    }

//    private int getVladimirState(int i, int j, int k) {
//        if (i == 1) i = 2;
//        else if (i == 2) i = 1;
//
//        if (j == 1) j = 2;
//        else if (j == 2) j = 1;
//
//        if (k == 1) k = 2;
//        else if (k == 2) k = 1;
//
//        return i * 16 + j * 4 + k + 1;
//    }

    public LogColumn[] getColumns() {
        return treeTraitLogger.getColumns();
    }

    public int getDimension() {
        return numCodons;
    }

    private void computeAllUnconditionalCountsPerBranch() {
        if (unconditionedCountsPerBranch == null) {
            unconditionedCountsPerBranch = new double[tree.getNodeCount()][numCodons];
        }
        double[] rootDistribution = productChainModel.getFrequencyModel().getFrequencies();
        for (int i = 0; i < tree.getNodeCount(); i++) {
            NodeRef node = tree.getNode(i);
            if (!tree.isRoot(node)) {
                final double expectedLength = getExpectedBranchLength(node);
                fillInUnconditionalTraitValues(expectedLength, rootDistribution, unconditionedCountsPerBranch[node.getNumber()]);
            }
        }
    }

    private void computeUnconditionedTraitValues() {
        if (unconditionedCounts == null) {
            unconditionedCounts = new double[numCodons];
        }
        final double treeLength = getExpectedTreeLength();
        double[] rootDistribution = productChainModel.getFrequencyModel().getFrequencies();
//        final int stateCount = 64;
//        double[] lambda = new double[stateCount * stateCount];
//        productChainModel.getInfinitesimalMatrix(lambda);
//        for (int i = 0; i < numCodons; i++) {
//            final int startingState = MathUtils.randomChoicePDF(rootDistribution);
//            StateHistory history = StateHistory.simulateUnconditionalOnEndingState(
//                    0.0,
//                    startingState,
//                    treeLength,
//                    lambda,
//                    stateCount
//            );
//            unconditionedCounts[i] = markovJumps.getProcessForSimulant(history);
//        }
        fillInUnconditionalTraitValues(treeLength, rootDistribution, unconditionedCounts);
    }

    private void fillInUnconditionalTraitValues(double expectedLength, double[] freq, double[] out) {
        final int stateCount = 64;
        double[] lambda = new double[stateCount * stateCount];
        productChainModel.getInfinitesimalMatrix(lambda);
        for (int i = 0; i < numCodons; i++) {
            final int startingState = MathUtils.randomChoicePDF(freq);
            StateHistory history = StateHistory.simulateUnconditionalOnEndingState(
                    0.0,
                    startingState,
                    expectedLength,
                    lambda,
                    stateCount
            );
            out[i] = markovJumps.getProcessForSimulant(history);
        }
    }

    private double[] getUnconditionedTraitValues() {
        if (!unconditionsKnown) {
            computeUnconditionedTraitValues();
            unconditionsKnown = true;
        }
        return unconditionedCounts;
    }

    public Double getUnconditionedTraitValue() {
        if (!TRIAL) {
            return markovJumps.getMarginalRate() * getExpectedTreeLength();
        } else {
            final double treeLength = getExpectedTreeLength();
            double[] rootDistribution = productChainModel.getFrequencyModel().getFrequencies();
            final int startingState = MathUtils.randomChoicePDF(rootDistribution);
            final int stateCount = 64;
            double[] lambda = new double[stateCount * stateCount];
            productChainModel.getInfinitesimalMatrix(lambda);
            StateHistory history = StateHistory.simulateUnconditionalOnEndingState(
                    0.0,
                    startingState,
                    treeLength,
                    lambda,
                    stateCount
            );
            return markovJumps.getProcessForSimulant(history);
        }
    }

    private double getExpectedBranchLength(NodeRef node) {
        return branchRateModel.getBranchRate(tree, node) * tree.getBranchLength(node);
    }

    private double getExpectedTreeLength() {
        double expectedTreeLength = 0;
        if (includeExternalBranches) {
            for (int i = 0; i < tree.getExternalNodeCount(); i++) {
                NodeRef node = tree.getExternalNode(i);
                expectedTreeLength += getExpectedBranchLength(node);
            }
        }
        if (includeInternalBranches) {
            for (int i = 0; i < tree.getInternalNodeCount(); i++) {
                NodeRef node = tree.getInternalNode(i);
                if (!tree.isRoot(node)) {
                    expectedTreeLength += getExpectedBranchLength(node);
                }
            }
        }
        return expectedTreeLength;
    }

    protected void handleModelChangedEvent(Model model, Object object, int index) {
        countsKnown = false;
        unconditionsKnown = false;
        unconditionsPerBranchKnown = false;
    }

    protected void handleVariableChangedEvent(Variable variable, int index, Parameter.ChangeType type) {
        countsKnown = false;
        unconditionsKnown = false;
    }

    protected void storeState() {
        // Do nothing
    }

    protected void restoreState() {
        countsKnown = false;
        unconditionsKnown = false;
        unconditionsPerBranchKnown = false;
    }

    protected void acceptState() {
        // Do nothing
    }

    private final AncestralStateBeagleTreeLikelihood[] partition;
    private final MarkovJumpsSubstitutionModel markovJumps;
    private final boolean useUniformization;
    private final BranchRateModel branchRateModel;
    private final ProductChainSubstitutionModel productChainModel;

    private final CodonLabeling codonLabeling;
    private final Tree tree;

    private final StratifiedTraitOutputFormat branchFormat;
    private final StratifiedTraitOutputFormat logFormat;

    private final double[] condMeanMatrix;

    private int numCodons;

    private boolean countsKnown = false;
    private boolean unconditionsKnown = false;
    private boolean unconditionsPerBranchKnown = false;
    private double[] unconditionedCounts;
    private double[][] unconditionedCountsPerBranch;
    private double[][] computedCounts; // TODO Temporary storage until generic TreeTraitProvider/Helpers are finished

    private String[][] completeHistoryPerNode;

    protected Helper treeTraits = new Helper();
    protected TreeTraitLogger treeTraitLogger;

    private final boolean includeExternalBranches;
    private final boolean includeInternalBranches;
    private final boolean doUnconditionedPerBranch;

    private static final boolean TRIAL = true;

    private boolean saveCompleteHistory = false;

}