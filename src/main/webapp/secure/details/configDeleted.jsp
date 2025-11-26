<%@page contentType="text/html" pageEncoding="UTF-8"
    import="org.apache.commons.io.FileUtils, org.starexec.data.database.Permissions, org.starexec.data.database.Solvers,org.starexec.data.security.GeneralSecurity, org.starexec.data.to.Configuration, org.starexec.data.to.Solver, org.starexec.util.SessionUtil, org.starexec.util.Util, java.io.File, org.starexec.logger.StarLogger"
    session="true" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<html lang="en">
<head>
    <title>Deleted Configuration - StarExec</title>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1" />
    <link rel="stylesheet" href="<c:url value='/css/jqueryui/jquery-ui.css'/>" />
    <link rel="stylesheet" href="<c:url value='/css/global.css'/>" />
    <link rel="stylesheet" href="<c:url value='/css/error.css'/>" />
    <script>
    var starexecRoot="<c:url value='/'/>";
    var defaultPageSize=10;
    var isLocalJobPage=false;
    var debugMode=false;
    </script>
    <script type="text/javascript" src="<c:url value='/js/lib/jquery.min.js'/>"></script>
    <script type="text/javascript" src="<c:url value='/js/lib/jquery-ui.min.js'/>"></script>
    <script type="text/javascript" src="<c:url value='/js/lib/jquery.cookie.js'/>"></script>
    <script type="text/javascript" src="<c:url value='/js/master.js'/>"></script>
    <link type="image/ico" rel="icon" href="<c:url value='/images/favicon.ico'/>">
</head>
<body>
<div id="wrapper">

    <header id="pageHeader">
    <div id="starexecLogoWrapper">
        <a href="<c:url value='/secure/index.jsp'/>"><img src="<c:url value='/images/starlogo.png'/>" alt="StarExec Logo"></a>
    </div>

    <div id="starexecNavWrapper">
        <nav role="navigation" aria-label="Main navigation">
        <ul role="menubar">
            <li role="none">
            <a href="#" role="menuitem" aria-haspopup="true" aria-expanded="false">Account</a>
            <ul class="subnav" role="menu" aria-label="Account submenu">
                <li role="none"><a role="menuitem" href="<c:url value='/secure/details/user.jsp?id=3'/>">Profile</a></li>
                <li role="none"><a role="menuitem" href="#" id="logoutLink">Logout</a></li>
            </ul>
            </li>

            <li role="none">
            <a href="#" role="menuitem" aria-haspopup="true" aria-expanded="false">Spaces</a>
            <ul class="subnav" role="menu" aria-label="Spaces submenu">
                <li role="none"><a role="menuitem" href="<c:url value='/secure/explore/spaces.jsp'/>">Explore</a></li>
                <li role="none"><a role="menuitem" href="<c:url value='/secure/explore/communities.jsp'/>">Communities</a></li>
                <li role="none"><a role="menuitem" href="<c:url value='/secure/explore/statistics.jsp'/>">Statistics</a></li>
                <li role="none"><a role="menuitem" href="<c:url value='/secure/explore/reports.jsp'/>">Reports</a></li>
            </ul>
            </li>
            <li role="none">
            <a href="#" role="menuitem" aria-haspopup="true" aria-expanded="false">Cluster</a>
            <ul class="subnav" role="menu" aria-label="Cluster submenu">
                <li role="none"><a role="menuitem" href="<c:url value='/secure/explore/cluster.jsp'/>">Status</a></li>
            </ul>
            </li>
            <li role="none" id="helpTab"><a id="helpTag" role="menuitem" href="<c:url value='/secure/help.jsp'/>">Help</a></li>
        </ul>
        </nav>
    </div>
    </header>
    <div id="content" class="round">
    <div id="mainHeaderWrapper">
        <h1 style="width:100%; word-wrap:break-word;" id="mainTemplateHeader">This configuration has been deleted.</h1>
    </div>
    <img alt="loading" src="<c:url value='/images/loader.gif'/>" id="loader">

    <p>Although this configuration file was used in the job you were just viewing, since then it has been deleted and no longer exists.</p>
    </div>

    <footer id="pageFooter" role="contentinfo">
    <nav aria-label="Footer navigation">
    <ul>
        <li><a target="_blank" rel="noopener" href="<c:url value='/secure/details/user.jsp?id=3'/>">Test User</a></li>
        <li aria-hidden="true">|</li>
        <li><a href="#" id="footerLogoutLink">Logout</a></li>
        <li aria-hidden="true">|</li>
        <li><a id="about" href="<c:url value='/public/about.jsp'/>">About</a></li>
        <li aria-hidden="true">|</li>
        <li><a id="help" href="<c:url value='/public/help.jsp'/>">Support</a></li>
        <li aria-hidden="true">|</li>
        <li><a id="starexeccommand" href="<c:url value='/public/starexeccommand.jsp'/>">StarExec Command</a></li>
    </ul>
    </nav>
    <a class="copyright" href="http://www.cs.uiowa.edu" target="_blank" rel="noopener">&copy;
        2012-18 The University of Iowa</a>
    </footer>

</div>
</body>
</html>
