package com.iuims.registrar;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Controller
public class RegistrarController {

    @Autowired private RegistrarService service;
    @Autowired private JdbcTemplate db;

    // --- AUTH ---
    @GetMapping("/login") public String loginPage() { return "login"; }
    @GetMapping("/logout") public String logout(HttpSession session) { session.invalidate(); return "redirect:/login"; }
    @PostMapping("/login") public String doLogin(@RequestParam String username, @RequestParam String password, HttpSession session, Model model) {
        Map<String, Object> user = service.login(username, password);
        if (user != null) { session.setAttribute("currentUser", user); return "redirect:/"; }
        model.addAttribute("error", "Invalid Credentials"); return "login";
    }

    // --- DASHBOARD ---
    @GetMapping("/") public String dashboard(HttpSession session, Model model) {
        if (session.getAttribute("currentUser") == null) return "redirect:/login";
        model.addAttribute("user", session.getAttribute("currentUser")); return "dashboard";
    }

    // --- DEAN MODULE (Fix Applied Here) ---
    @GetMapping("/faculty/student-manager")
    public String deanManagerPage(HttpSession session) {
        if (!hasPermission(session, "MANAGE_LOAD")) return "redirect:/";
        return "dean_student_manager";
    }

    @GetMapping("/faculty/manage-student")
    public String manageStudentSearch(@RequestParam String username, Model model, HttpSession session) {
        if (!hasPermission(session, "MANAGE_LOAD")) return "redirect:/";
        try {
            Map<String, Object> student = db.queryForMap("SELECT * FROM sys_users WHERE username = ?", username);
            model.addAttribute("student", student);
            model.addAttribute("studentLoad", service.getStudentLoad((int)student.get("user_id")));
            model.addAttribute("allClasses", service.getAllClasses());
        } catch (Exception e) { model.addAttribute("message", "Student not found."); }
        return "dean_student_manager";
    }

    @PostMapping("/faculty/request-add")
    public String requestAdd(@RequestParam int studentId, @RequestParam int scheduleId, Model model, HttpSession session) {
        if (!hasPermission(session, "MANAGE_LOAD")) return "redirect:/";
        String res = service.addSubject(studentId, scheduleId);
        // FIX: Passing 'session' prevents the crash
        return reloadDean(studentId, model, res, session);
    }

    @PostMapping("/faculty/request-drop")
    public String requestDrop(@RequestParam int studentId, @RequestParam int scheduleId, Model model, HttpSession session) {
        if (!hasPermission(session, "MANAGE_LOAD")) return "redirect:/";
        String res = service.dropSubject(studentId, scheduleId);
        // FIX: Passing 'session' prevents the crash
        return reloadDean(studentId, model, res, session);
    }

    // FIXED HELPER: Signature updated to accept HttpSession
    private String reloadDean(int sid, Model m, String msg, HttpSession session) { 
        m.addAttribute("message", msg); 
        try {
            String username = db.queryForObject("SELECT username FROM sys_users WHERE user_id=?", String.class, sid);
            return manageStudentSearch(username, m, session);
        } catch (Exception e) { return "redirect:/"; }
    }

    // --- ADMIN MODULES ---
    @GetMapping("/admin") public String adminPage(Model model) { model.addAttribute("users", service.getAllUsers()); model.addAttribute("logs", service.getAuditLogs()); return "admin_users"; }
    @PostMapping("/create-user") public String createUser(@RequestParam String username, @RequestParam String realName, @RequestParam String role, @RequestParam String program, @RequestParam(required=false) List<String> permissions) { service.registerUser(username, realName, role, permissions, program); return "redirect:/admin"; }
    @PostMapping("/admin/update-user") public String updateUser(@RequestParam int userId, @RequestParam String role, @RequestParam(required=false) List<String> permissions) { service.updateUser(userId, role, permissions); return "redirect:/admin"; }
    @PostMapping("/admin/delete-user") public String deleteUser(@RequestParam int userId) { service.deleteUser(userId); return "redirect:/admin"; }
    @PostMapping("/admin/toggle-status") public String toggleStatus(@RequestParam int userId, @RequestParam boolean isActive) { service.toggleUserStatus(userId, isActive); return "redirect:/admin"; }
    @PostMapping("/admin/reset-password") public String resetPassword(@RequestParam int userId) { service.resetPassword(userId); return "redirect:/admin"; }

    // --- APPROVALS (This populates the Admin Tab) ---
    @GetMapping("/admin/approvals") 
    public String approvalPage(Model model) { 
        model.addAttribute("enrollments", service.getPendingEnrollments()); 
        model.addAttribute("gradeRequests", service.getPendingGradeRequests()); 
        return "admin_approvals"; 
    }
    
    @PostMapping("/admin/approve-enrollment") public String approveEnrollment(@RequestParam int detailId) { service.approveEnrollment(detailId); return "redirect:/admin/approvals"; }
    @PostMapping("/admin/approve-grade") public String approveGradeChange(@RequestParam int requestId) { service.approveGradeChange(requestId); return "redirect:/admin/approvals"; }

    // --- CLASSES ---
    @GetMapping("/admin/classes") public String adminClasses(Model model) { model.addAttribute("classes", service.getAllClasses()); model.addAttribute("facultyList", service.getFacultyList()); return "admin_classes"; }
    @PostMapping("/create-class") public String createClass(@RequestParam String code, @RequestParam String section, @RequestParam String day, @RequestParam int start, @RequestParam int end) { service.createClass(code, section, day, start, end, 40); return "redirect:/admin/classes"; }
    @PostMapping("/assign-faculty") public String assignFaculty(@RequestParam int scheduleId, @RequestParam int facultyId) { service.assignFaculty(scheduleId, facultyId); return "redirect:/admin/classes"; }

    // --- STUDENT ---
    @GetMapping("/enrollment") public String enrollmentPage(HttpSession session, Model model) { 
        if (!isStudent(session)) return "redirect:/"; 
        model.addAttribute("classes", service.getAllClasses()); 
        return "enrollment"; 
    }
    @GetMapping("/my-load") public String myLoad(HttpSession session, Model model) { Map<String, Object> u = (Map<String, Object>) session.getAttribute("currentUser"); model.addAttribute("myClasses", service.getStudentLoad((int)u.get("user_id"))); model.addAttribute("studentName", u.get("real_name")); return "report_study_load"; }
    @GetMapping("/my-grades") public String myGrades(HttpSession session, Model model) { Map<String, Object> u = (Map<String, Object>) session.getAttribute("currentUser"); model.addAttribute("myGrades", service.getStudentGrades((int)u.get("user_id"))); return "student_grades"; }

    // --- FACULTY GRADING ---
    @GetMapping("/grades") public String gradesMenuPage(HttpSession session, Model model) { Map<String, Object> u = (Map<String, Object>) session.getAttribute("currentUser"); model.addAttribute("classes", "Faculty".equals(u.get("role")) ? service.getFacultyClasses((int)u.get("user_id")) : service.getAllClasses()); return "grades_menu"; }
    @GetMapping("/grades/view/{id}") public String classGradesPage(@PathVariable int id, HttpSession session, Model model) { 
        Map<String, Object> user = (Map<String, Object>) session.getAttribute("currentUser");
        if (user == null) return "redirect:/login";
        model.addAttribute("user", user); 
        model.addAttribute("classInfo", service.getClassDetails(id)); 
        model.addAttribute("grades", db.queryForList("SELECT * FROM student_grades WHERE schedule_id = ? ORDER BY student_name", id)); 
        return "grades_sheet"; 
    }
    @PostMapping("/faculty/save-grade") public String saveGrade(@RequestParam int gradeId, @RequestParam String grade, @RequestParam int scheduleId) { service.inputGrade(gradeId, grade); return "redirect:/grades/view/" + scheduleId; }
    @PostMapping("/faculty/submit-class") public String submitClass(@RequestParam int scheduleId) { service.submitGrades(scheduleId); return "redirect:/grades/view/" + scheduleId; }
    @PostMapping("/faculty/request-change") public String requestChange(@RequestParam int gradeId, @RequestParam String newGrade, @RequestParam String reason, @RequestParam int scheduleId, HttpSession session) { Map<String, Object> u = (Map<String, Object>) session.getAttribute("currentUser"); service.requestGradeChange(gradeId, newGrade, reason, (int)u.get("user_id")); return "redirect:/grades/view/" + scheduleId + "?msg=RequestSent"; }

    // --- REPORTS ---
    @GetMapping("/class-list/{id}") public String viewClassList(@PathVariable int id, Model model) { 
        model.addAttribute("classInfo", service.getClassDetails(id)); 
        model.addAttribute("students", service.getClassList(id)); 
        model.addAttribute("scheduleId", id); 
        return "report_class_list"; 
    }

    // --- CURRICULUM ---
    @GetMapping("/admin/curriculum") public String adminCurriculum(Model model) { model.addAttribute("curriculum", service.getActiveCurriculum()); model.addAttribute("activeYear", service.getActiveCurriculumYear()); return "admin_curriculum"; }
    @PostMapping("/admin/set-active-year") public String setActiveYear(@RequestParam int year) { service.setActiveCurriculumYear(year); return "redirect:/admin/curriculum"; }
    @PostMapping("/admin/add-course") public String addCourse(@RequestParam String code, @RequestParam String desc, @RequestParam int units, @RequestParam int year, @RequestParam int sem, @RequestParam int currYear) { service.addCourseToCurriculum(code, desc, units, year, sem, currYear); return "redirect:/admin/curriculum"; }
    @PostMapping("/admin/delete-course") public String deleteCourse(@RequestParam String code, @RequestParam int currYear) { service.removeCourseFromCurriculum(code, currYear); return "redirect:/admin/curriculum"; }

    private boolean isStudent(HttpSession session) { Map<String, Object> u = (Map<String, Object>) session.getAttribute("currentUser"); return u != null && "Student".equals(u.get("role")); }
    
    private boolean hasPermission(HttpSession session, String perm) { 
        if(session == null) return false;
        Map<String, Object> u = (Map<String, Object>) session.getAttribute("currentUser"); 
        return u != null && u.get("granted_permissions") != null && ((String)u.get("granted_permissions")).contains(perm); 
    }
}