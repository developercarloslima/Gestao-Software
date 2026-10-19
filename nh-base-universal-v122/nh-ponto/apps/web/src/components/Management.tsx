import { useState } from 'react';
import { getCachedUser } from '../lib/api';
import Overview from './management/Overview';
import Employees from './management/Employees';
import Shifts from './management/Shifts';
import Worksites from './management/Worksites';
import Supervisors from './management/Supervisors';
import Bank from './management/Bank';
import Payroll from './management/Payroll';
import Settings from './management/Settings';
import Devices from './management/Devices';
import Departments from './management/Departments';
import PresenceCodes from './management/PresenceCodes';
import SecurityReviews from './management/SecurityReviews';
import Birthdays from './management/Birthdays';
import AbsenceJustifications from './management/AbsenceJustifications';
import DailyAttendance from './management/DailyAttendance';
import ScreenMonitoring from './management/ScreenMonitoring';
import Audit from './Audit';
import TimesheetViewer from './management/TimesheetViewer';
import TeamAdjustments from './management/TeamAdjustments';

export default function Management(){
 const user=getCachedUser(); const pointReviewer=user?.role==='TIME_REVIEWER'; const detailedTimesheet=user?.role==='SUPER_ADMIN'||pointReviewer; const canAdmin=['SUPER_ADMIN','ADMIN','HR'].includes(user?.role??''); const canReview=['SUPER_ADMIN','ADMIN','TIME_REVIEWER'].includes(user?.role??''); const canShiftAdmin=['SUPER_ADMIN','ADMIN'].includes(user?.role??''); const canGlobalAudit=user?.role==='SUPER_ADMIN';
 const [tab,setTab]=useState(pointReviewer?'presenca-hoje':'visao');
 const tabs=pointReviewer
   ? [['presenca-hoje','Presença hoje'],['seguranca','Aprovação de atrasos'],['espelhos','Espelho de pontos']]
   : [['visao','Visão geral'],['equipe','Equipe'],...(canAdmin?[['aniversarios','Aniversariantes']]:[]),...(canTimeOff?[['ferias','Férias'],['feriados','Feriados']]:[]),...(canAdmin?[['setores','Setores']]:[]),...(canShiftAdmin?[['escalas','Jornadas e escalas'],['supervisores','Supervisores']]:[]),...(canAdmin?[['locais','Locais'],['presenca','QR de presença'],['dispositivos','Dispositivos']]:[]),...(canReview?[['presenca-hoje','Presença hoje'],['monitoramento','Contadores de tempo'],['seguranca','Aprovação de atrasos'],['justificativas','Justificativas de falta']]:[]),...(canAdmin?[['ajustes-equipe','Ajustes de marcações']]:[]),['banco','Banco de horas'],...(canAdmin?[['folha','Espelho de ponto / folha'],['regras','Configurações']]:[]),...(user?.role==='SUPER_ADMIN'?[['espelhos','Espelho de pontos']]:[]),...(canGlobalAudit?[['auditoria','Auditoria']]:[])];
 return <><div className="subnav">{tabs.map(([id,label])=><button key={id} className={tab===id?'active':''} onClick={()=>setTab(id)}>{label}</button>)}</div>{!pointReviewer&&tab==='visao'&&<Overview/>}{!pointReviewer&&tab==='equipe'&&<Employees editable={canAdmin}/>} {!pointReviewer&&tab==='aniversarios'&&canAdmin&&<Birthdays/>}{!pointReviewer&&tab==='ferias'&&canTimeOff&&<TimeOff mode="vacations"/>}{!pointReviewer&&tab==='feriados'&&canTimeOff&&<TimeOff mode="holidays"/>} {!pointReviewer&&tab==='setores'&&canAdmin&&<Departments/>}{!pointReviewer&&tab==='escalas'&&canShiftAdmin&&<Shifts/>}{!pointReviewer&&tab==='supervisores'&&canShiftAdmin&&<Supervisors/>}{!pointReviewer&&tab==='locais'&&canAdmin&&<Worksites/>}{!pointReviewer&&tab==='presenca'&&canAdmin&&<PresenceCodes/>}{!pointReviewer&&tab==='dispositivos'&&canAdmin&&<Devices/>}{tab==='presenca-hoje'&&canReview&&<DailyAttendance/>}{!pointReviewer&&tab==='monitoramento'&&canReview&&<ScreenMonitoring/>}{tab==='seguranca'&&canReview&&<SecurityReviews/>}{!pointReviewer&&tab==='justificativas'&&canReview&&<AbsenceJustifications/>}{!pointReviewer&&tab==='ajustes-equipe'&&canAdmin&&<TeamAdjustments/>}{!pointReviewer&&tab==='banco'&&<Bank editable={canAdmin}/>}{!pointReviewer&&tab==='folha'&&canAdmin&&<Payroll/>}{detailedTimesheet&&tab==='espelhos'&&<TimesheetViewer/>}{!pointReviewer&&tab==='regras'&&canAdmin&&<Settings/>}{!pointReviewer&&tab==='auditoria'&&canGlobalAudit&&<Audit/>}</>;
}
