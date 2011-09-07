<%@page import="org.apache.tomcat.util.http.Parameters"%>
<%@page import="org.apache.jasper.tagplugins.jstl.core.Param"%>
<%@page import="com.starexec.data.*"%>
<%@page import="com.starexec.data.to.*"%>
<%@page import="java.util.*"%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1" import="com.starexec.constants.*"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
	<meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
	<title><%=T.REGISTER %></title>
	<%@ include file="/includes/jQuery.html" %>
	<link type="text/css" rel="StyleSheet" href="/starexec/css/maincontent.css" />
	<link type="text/css" rel="StyleSheet" href="/starexec/css/register.css" />
</head>

<body>
	<div id="wrapper">
		<jsp:include page="/includes/header.jsp" />
		<div class="content round" style="height: 350px;">
			<h1>Register</h1>
			<a class="help" href="#">Help</a>
						
			<form method="POST" action="Registration" id="formRegister">			
				<table cellspacing="10">					
					<tr>
						<td class="reg_lable">Email </td>
						<td><input type="text" name="<%=P.USER_EMAIL %>" /></td>
					</tr>
					<tr>
						<td class="reg_lable">First Name </td>
						<td><input type="text" name="<%=P.USER_FIRSTNAME%>" /></td>
					</tr>
					<tr>
						<td class="reg_lable">Last Name </td>
						<td><input type="text" name="<%=P.USER_LASTNAME%>" /></td>
					</tr>
					<tr>
						<td class="reg_lable">Affiliation </td>
						<td><input type="text" name="<%=P.USER_AFILIATION%>" /></td>
					</tr>
					<tr>
						<td class="reg_lable">Community </td>
						<td>
							<select name="<%=P.USER_COMMUNITY%>" style="width:100%;">
								<% request.setAttribute("communities", Databases.next().getAllCommunities());%>
								<c:forEach var="community" items="${communities}">
						        	<option value="${community.id}">${community.name}</option>						          
						      </c:forEach>
							</select>
						</td>
					</tr>
					<tr>
						<td class="reg_lable">Password </td>
						<td><input id="pass" type="password" name="<%=P.USER_PASSWORD%>"/ ></td>
					</tr>
					<tr>
						<td class="reg_lable">Confirm Password </td>
						<td><input id="confirm_pass" type="password" / ></td>
					</tr>								
					<tr>
						<td colspan="2"><a onclick="$('#formRegister').submit()" class="btn ui-state-default ui-corner-all" id="btnRegister"><span class="ui-icon ui-icon-locked right"></span>Register</a></td>
					</tr>
				</table>				
			</form>					
		</div>		
	</div>
</body>
</html>