/*-
 * #%L
 * A Fiji plugin to track the dynamics of dendrites in 2D time-lapse movies.
 * %%
 * Copyright (C) 2019 - 2021 Institut Pasteur
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.ImageIcon;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Dimension;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.features.spot.SpotAnalyzer;
import fiji.plugin.trackmate.features.spot.SpotAnalyzerFactory;
import net.imagej.ImgPlus;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

@Plugin( type = SpotAnalyzerFactory.class )
public class JunctionIDAnalyzerFactory< T extends RealType< T > & NativeType< T > > implements SpotAnalyzerFactory< T >
{

	public static final String FEATURE = "JUNCTION_ID";

	public static final String KEY = "JUNCTION_ID_ANALYZER";

	static final List< String > FEATURES = new ArrayList<>( 1 );

	static final Map< String, String > FEATURE_SHORT_NAMES = new HashMap<>( 1 );

	static final Map< String, String > FEATURE_NAMES = new HashMap<>( 1 );

	static final Map< String, Dimension > FEATURE_DIMENSIONS = new HashMap<>( 1 );

	static final Map< String, Boolean > IS_INT = new HashMap<>( 1 );

	static final String INFO_TEXT = "<html>A dummy analyzer for the feature that stores the ID of the junction of a skeleton end-point.</html>";

	static final String NAME = "Junction ID";

	static
	{
		FEATURES.add( FEATURE );
		FEATURE_SHORT_NAMES.put( FEATURE, NAME );
		FEATURE_NAMES.put( FEATURE, NAME );
		FEATURE_DIMENSIONS.put( FEATURE, Dimension.NONE );
		IS_INT.put( FEATURE, Boolean.TRUE );
	}

	@Override
	public String getKey()
	{
		return KEY;
	}

	@Override
	public List< String > getFeatures()
	{
		return FEATURES;
	}

	@Override
	public Map< String, String > getFeatureShortNames()
	{
		return FEATURE_SHORT_NAMES;
	}

	@Override
	public Map< String, String > getFeatureNames()
	{
		return FEATURE_NAMES;
	}

	@Override
	public Map< String, Dimension > getFeatureDimensions()
	{
		return FEATURE_DIMENSIONS;
	}

	@Override
	public String getInfoText()
	{
		return INFO_TEXT;
	}

	@Override
	public Map< String, Boolean > getIsIntFeature()
	{
		return IS_INT;
	}

	@Override
	public boolean isManualFeature()
	{
		return true;
	}

	@Override
	public ImageIcon getIcon()
	{
		return null;
	}

	@Override
	public String getName()
	{
		return NAME;
	}


	@Override
	public SpotAnalyzer< T > getAnalyzer( final ImgPlus< T > img, final int frame, final int channel )
	{
		return new SpotAnalyzer< T >()
		{

			@Override
			public void process( final Iterable< Spot > spots )
			{
				for ( final Spot spot : spots )
				{
					if ( null == spot.getFeature( FEATURE ) )
						spot.putFeature( FEATURE, Double.valueOf( -1. ) );
				}
			}
		};
	}
}
