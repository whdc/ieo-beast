/*
 * YuleModel.java
 *
 * Copyright (C) 2002-2009 Alexei Drummond and Andrew Rambaut
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
 * BEAST is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package dr.evomodel.speciation;

import dr.evolution.tree.NodeRef;
import dr.evolution.util.Taxon;
import dr.evolution.tree.Tree;
import dr.evomodelxml.speciation.YuleModelParser;
import dr.inference.model.Parameter;

/**
 * From Gernhard 2008, Yule density (p; conditioned on n nodes) should be:
 * <p/>
 * double p = 0.0;
 * <p/>
 * p = lambda^(n-1) * exp(-lambda*rootHeight);
 * <p/>
 * for (int i = 1; i < n; i++) {
 * p *= exp(-lambda*height[i])
 * }
 *
 * @author Alexei Drummond
 * @author Roald Forsberg
 */
public class YuleModel extends UltrametricSpeciationModel {




    public YuleModel(Parameter birthRateParameter, Type units) {

        super("Will Chang's Yule Model", units);

        this.birthRateParameter = birthRateParameter;
        addVariable(birthRateParameter);
        birthRateParameter.addBounds(new Parameter.DefaultBounds(Double.POSITIVE_INFINITY, 0.0, 1));
    }

    public double getBirthRate() {
        return birthRateParameter.getParameterValue(0);
    }

    public void setBirthRate(double birthRate) {

        birthRateParameter.setParameterValue(0, birthRate);
    }

    //
    // functions that define a speciation model
    //
    public double logTreeProbability(int taxonCount) {
        final double lambda = getBirthRate();
        return (taxonCount - 1) * Math.log( lambda);
    }

    //
    // functions that define a speciation model
    //
    public double logNodeProbability(Tree tree, NodeRef node) {

        double nodeHeight = tree.getNodeHeight(node);
        final double lambda = getBirthRate();

        double logP = 0;

        if (tree.isRoot(node)) {
            logP += -2 * lambda * nodeHeight;
        } else if (tree.getChildCount(node) == 0) {
            Taxon taxon = tree.getNodeTaxon( node);
            boolean isAdventitious = taxon != null && taxon.getAdventitious();
            if( isAdventitious) {
              // An adventitious doesn't count as a taxon
              logP -= Math.log( lambda);
            } else {
              logP -= -lambda * nodeHeight;
            }
        } else {
            Taxon taxon0 = tree.getNodeTaxon( tree.getChild(node, 0));
            Taxon taxon1 = tree.getNodeTaxon( tree.getChild(node, 1));
            boolean hasAdventitiousChild =
              (taxon0 != null && taxon0.getAdventitious()) || 
              (taxon1 != null && taxon1.getAdventitious());
            // Don't reckon the branch leading up to an adventitious leaf.
            if( !hasAdventitiousChild) {
              logP += -lambda * nodeHeight;
            }
        }

        return logP;
    }

    public boolean includeExternalNodesInLikelihoodCalculation() {
        return false;
    }

    // **************************************************************
    // XMLElement IMPLEMENTATION
    // **************************************************************

    public org.w3c.dom.Element createElement(org.w3c.dom.Document d) {
        throw new RuntimeException("createElement not implemented");
    }


    //Protected stuff
    Parameter birthRateParameter;

}
