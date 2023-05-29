/*-
 * #%L
 * A Fiji plugin to track the dynamics of dendrites in 2D time-lapse movies.
 * %%
 * Copyright (C) 2019 - 2023 Institut Pasteur
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the Institut Pasteur / IAH nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package fr.pasteur.iah.dendritedynamicstracker.trackmate.feature;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.swing.ImageIcon;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Dimension;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.features.track.TrackAnalyzer;

@Plugin( type = TrackAnalyzer.class )
public class DendriteTrackNIncorrectIDs implements TrackAnalyzer
{

	public static final String FEATURE = "N_INCORRECT_ID";

	private static final String KEY = FEATURE;

	private static final String INFO_TEXT = "<html>"
			+ "This track feature stores the number of incorrect junction IDs its end-point spots link to."
			+ "<p>"
			+ "In ideal situation a dendrite track is made of a collection of end-points in the neuron "
			+ "skeleton that always link to the same junction track ID. Because of incorrect tracking "
			+ "either for the end-points or for the junctions, it might not be the case. This feature "
			+ "stores the number of spots in the track that do not link to the main junction track ID. "
			+ "A value of 0 means that the end-point track has no defects."
			+ "</html>";

	@Override
	public long getProcessingTime()
	{
		return 0;
	}

	@Override
	public List< String > getFeatures()
	{
		return Collections.singletonList( FEATURE );
	}

	@Override
	public Map< String, String > getFeatureShortNames()
	{
		return Collections.singletonMap( FEATURE, "N. incorr. IDs" );
	}

	@Override
	public Map< String, String > getFeatureNames()
	{
		return Collections.singletonMap( FEATURE, "N incorrect junction IDs" );
	}

	@Override
	public Map< String, Dimension > getFeatureDimensions()
	{
		return Collections.singletonMap( FEATURE, Dimension.NONE );
	}

	@Override
	public Map< String, Boolean > getIsIntFeature()
	{
		return Collections.singletonMap( FEATURE, Boolean.TRUE );
	}

	@Override
	public boolean isManualFeature()
	{
		return true;
	}

	@Override
	public String getInfoText()
	{
		return INFO_TEXT;
	}

	@Override
	public ImageIcon getIcon()
	{
		return null;
	}

	@Override
	public String getKey()
	{
		return KEY;
	}

	@Override
	public String getName()
	{
		return "Dendrite track N incorrect ID feature";
	}

	@Override
	public void setNumThreads()
	{}

	@Override
	public void setNumThreads( final int numThreads )
	{}

	@Override
	public int getNumThreads()
	{
		return 0;
	}

	@Override
	public void process( final Collection< Integer > trackIDs, final Model model )
	{
		// Clear values.
		for ( final Integer trackID : trackIDs )
			model.getFeatureModel().putTrackFeature( trackID, FEATURE, Double.valueOf( -1. ) );
	}

	@Override
	public boolean isLocal()
	{
		return true;
	}
}
