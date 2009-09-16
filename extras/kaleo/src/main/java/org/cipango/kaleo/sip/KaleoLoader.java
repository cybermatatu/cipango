// ========================================================================
// Copyright 2008-2009 NEXCOM Systems
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cipango.kaleo.sip;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.cipango.kaleo.location.LocationService;
import org.cipango.kaleo.location.event.RegEventPackage;
import org.cipango.kaleo.presence.PresenceEventPackage;
import org.slf4j.Logger;

public class KaleoLoader implements ServletContextListener
{
	private Logger _log = org.slf4j.LoggerFactory.getLogger(KaleoLoader.class);
	
	public void contextDestroyed(ServletContextEvent event)
	{
		try
		{
			LocationService ls = (LocationService) event.getServletContext().getAttribute(LocationService.class.getName());
			if (ls != null)
				ls.stop();

			PresenceEventPackage presence = (PresenceEventPackage) event.getServletContext().getAttribute(PresenceEventPackage.class.getName());
			if (presence != null)
				presence.stop();
			
			RegEventPackage regEventPackage = (RegEventPackage) event.getServletContext().getAttribute(RegEventPackage.class.getName());
			if (regEventPackage != null)
				regEventPackage.stop();
		}
		catch (Exception e)
		{
			_log.warn("error while stopping Kaleo application", e);
		}
	}

	public void contextInitialized(ServletContextEvent event) 
	{
		try
		{
			PresenceEventPackage presence = new PresenceEventPackage();
			LocationService locationService = new LocationService();
			RegEventPackage regEventPackage = new RegEventPackage(locationService);
			
			locationService.start();
			presence.start();
			regEventPackage.start();
			
			event.getServletContext().setAttribute(PresenceEventPackage.class.getName(), presence);
			event.getServletContext().setAttribute(LocationService.class.getName(), locationService);
			event.getServletContext().setAttribute(RegEventPackage.class.getName(), regEventPackage);
		}
		catch (Exception e)
		{
			_log.error("failed to start Kaleo application", e);
		}
	}
}