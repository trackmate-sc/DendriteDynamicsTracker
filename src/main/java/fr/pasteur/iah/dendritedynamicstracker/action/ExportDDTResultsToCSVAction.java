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
package fr.pasteur.iah.dendritedynamicstracker.action;

import java.awt.Frame;

import javax.swing.ImageIcon;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.action.AbstractTMAction;
import fiji.plugin.trackmate.action.TrackMateAction;
import fiji.plugin.trackmate.action.TrackMateActionFactory;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import fr.pasteur.iah.dendritedynamicstracker.DendriteDynamicsCSVExporter;

public class ExportDDTResultsToCSVAction extends AbstractTMAction
{

	public static final ImageIcon ICON = new ImageIcon( ExportDDTResultsToCSVAction.class.getResource( "images/page_white_excel.png" ) );

	public static final String NAME = "Export Dendrite Dynamics Tracker results to CSV";

	public static final String KEY = "EXPORT_DDT_TO_CSV";

	public static final String INFO_TEXT = "<html>"
			+ "This action exports the results of the <b>Dendrite Dynamics Tracker</b>, present "
			+ "in this TrackMate instance, as multiple CSV files. "
			+ "<p> "
			+ "<p> "
			+ "The files exported are the same than the ones created when checking the "
			+ "<it>Export branch lengths and statistics to CSV files</it> box in the "
			+ "Dendrite Dynamics Tracker command window. "
			+ "</html>";

	@Override
	public void execute( final TrackMate trackmate, final SelectionModel selectionModel, final DisplaySettings displaySettings, final Frame parent )
	{
		logger.log( "Exporting to CSV files...\n" );
		final DendriteDynamicsCSVExporter exporter = new DendriteDynamicsCSVExporter( trackmate );
		if ( !exporter.checkInput() || !exporter.process() )
			logger.error( "Error while exporting results:\n" + exporter.getErrorMessage() );
		else
			logger.log( "Done.\n" );
	}

	@Plugin( type = TrackMateActionFactory.class )
	public static class Factory implements TrackMateActionFactory
	{

		@Override
		public String getInfoText()
		{
			return INFO_TEXT;
		}

		@Override
		public String getKey()
		{
			return KEY;
		}

		@Override
		public ImageIcon getIcon()
		{
			return ICON;
		}

		@Override
		public String getName()
		{
			return NAME;
		}

		@Override
		public TrackMateAction create()
		{
			return new ExportDDTResultsToCSVAction();
		}
	}
}
