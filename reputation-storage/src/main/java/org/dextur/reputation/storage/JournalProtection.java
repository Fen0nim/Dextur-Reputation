package org.dextur.reputation.storage;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;

final class JournalProtection {
    record Result(StorageSnapshot state,VoteReceipt receipt) { }
    static View context(StorageSnapshot state,UUID voter,UUID target,String scope,ProtectionRules rules,Instant now) {
        var p=state.protection();String pairKey=scope+"/"+voter+"/"+target;
        var pair=p.pairs().stream().filter(r->r.key().equals(pairKey)).findFirst().orElse(null);
        var recent=state.votes().stream().filter(v->v.scope().equals(scope)&&!v.createdAt().isBefore(now.minusSeconds(rules.lookbackDays()*86400L))
                &&(v.voterId().equals(voter)||v.targetId().equals(voter)||v.voterId().equals(target)||v.targetId().equals(target))).sorted(Comparator.comparing(VoteEntry::createdAt).thenComparing(v->v.operationId().toString()).reversed()).limit(rules.analysis().maxEvents()+1L).toList();
        var assessments=new HashMap<UUID,Assessment>();for(var a:p.assessments()) assessments.put(a.voteId(),a);
        var sample=recent.stream().limit(rules.analysis().maxEvents()).map(v->{
            var a=assessments.get(v.operationId());return new Sample(v,a!=null,a==null?0:a.factors().getOrDefault("trust_score",0L),a==null?0:a.factors().getOrDefault("maturity",0L));
        }).toList();
        return new View(p.trusts().stream().filter(t->t.player().equals(voter)&&t.scope().equals(scope)).findFirst().orElse(null),pair,
                p.scores().stream().filter(s->s.player().equals(target)&&s.scope().equals(scope)).findFirst().orElse(null),
                pair==null?null:assessments.get(pair.latestVote()),pair==null||pair.activeVote().isEmpty()?null:assessments.get(UUID.fromString(pair.activeVote())),
                p.scopes().stream().filter(s->s.scope().equals(scope)).findFirst().orElse(ScopeState.empty(scope)),sample,recent.size()<=rules.analysis().maxEvents());
    }
    static Result apply(StorageSnapshot state,String scope,VoteRequest request,VoterFacts facts,VotingRules voting,ProtectionRules rules,Instant now) {
        var existing=state.votes().stream().filter(v->v.operationId().equals(request.operationId())).findFirst().orElse(null);
        if(existing!=null) { VotingEngine.checkDuplicate(scope,request,existing);return new Result(state,new VoteReceipt(existing,true,JournalVoting.view(state,request.targetId(),scope,now))); }
        if(state.protection().audit().stream().anyMatch(a->a.id().equals(request.operationId()))) throw new VoteRejectedException(VoteRejectedException.Code.ID_CONFLICT);
        var voter=JournalVoting.profile(state,request.voterId(),scope,now);var target=JournalVoting.view(state,request.targetId(),scope,now);
        VoteEntry previous=null;Instant last=null;int today=0;
        for(var vote:state.votes()) if(vote.scope().equals(scope)&&vote.voterId().equals(request.voterId())) {
            if(vote.targetId().equals(request.targetId())) previous=vote;
            if(last==null||vote.createdAt().isAfter(last)) last=vote.createdAt();
            if(!vote.createdAt().isBefore(voting.dayStart(now))&&vote.createdAt().isBefore(voting.dayEnd(now))) today++;
        }
        long sequence=state.votes().isEmpty()?1:Math.incrementExact(state.votes().getLast().sequence());
        var change=ProtectionEngine.vote(scope,request,facts,voting,rules,now,new VotingEngine.Context(voter,target,previous,last,today,sequence),context(state,request.voterId(),request.targetId(),scope,rules,now));
        var next=change(state,change,voter);
        return new Result(next,new VoteReceipt(change.vote(),false,JournalVoting.view(next,request.targetId(),scope,now)));
    }
    static StorageSnapshot change(StorageSnapshot state,Change change,ReputationSnapshot voter) {
        var profiles=new ArrayList<>(state.profiles());profiles.removeIf(p->p.playerId().equals(change.profile().playerId())&&p.scopeId().equals(change.profile().scopeId()));
        profiles.add(change.profile());if(voter!=null&&profiles.stream().noneMatch(p->p.playerId().equals(voter.playerId())&&p.scopeId().equals(voter.scopeId()))) profiles.add(voter);
        var votes=new ArrayList<>(state.votes());if(change.vote()!=null) votes.add(change.vote());
        return new StorageSnapshot(profiles,state.preferences(),votes,state.protection().apply(change),TierSnapshot.empty(),state.recovery(),state.administration(),state.investigation());
    }
    record Analysis(ScopeState scope,Audit event) { }
    static Analysis analysis(View view,VoteEntry latest,long before,ProtectionRules rules,Instant now) {
        if(!rules.analysis().enabled()||latest==null||latest.sequence()<=view.scope().graphRevision()||now.isBefore(view.scope().graphAt().plusSeconds(rules.analysis().intervalSeconds()))) return null;
        var memo=AbuseAnalysis.backgroundGraph(view,latest,rules,now);var old=view.scope();
        UUID id=UUID.nameUUIDFromBytes((latest.scope()+"/analysis/"+latest.sequence()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var scope=new ScopeState(old.scope(),old.revision()+1,old.nextAudit()+1,memo.graphAt(),memo.graphRevision(),memo.clusters(),memo.graphComplete(),memo.graphWork());
        var event=new Audit(id,old.nextAudit(),old.scope(),latest.operationId(),latest.voterId(),latest.targetId(),"ANALYSIS",memo.clustered().isEmpty()?Status.ACCEPTED:Status.SUSPICIOUS,
                before,before,0,0,"","",now,"SYSTEM","Bounded relationship analysis v1",old.revision(),Map.of("analysis_version",1L,"graph_work",memo.graphWork(),"complete",memo.graphComplete()?1L:0L,"vote_sequence",latest.sequence()),memo.clustered());
        return new Analysis(scope,event);
    }
    static VoteProcessingOutcome outcome(StorageSnapshot state,UUID id) {
        var a=state.protection().assessments().stream().filter(row->row.voteId().equals(id)).findFirst().orElse(null);
        if(a!=null) return new VoteProcessingOutcome(id,publicStatus(a.status()));
        var v=state.votes().stream().filter(row->row.operationId().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown vote"));
        return new VoteProcessingOutcome(id,v.action()==VoteEntry.Action.REVOKE?VoteProcessingOutcome.Status.REVOKED:
                ProtectionValidation.active(state.votes(),state.protection()).values().stream().anyMatch(e->e.operationId().equals(id))?VoteProcessingOutcome.Status.ACCEPTED:VoteProcessingOutcome.Status.SUPERSEDED);
    }
    static VoteProcessingOutcome.Status publicStatus(Status status) {
        return switch(status) { case ACCEPTED,SUSPICIOUS -> VoteProcessingOutcome.Status.ACCEPTED;case PENDING -> VoteProcessingOutcome.Status.PENDING;
            case REVOKED -> VoteProcessingOutcome.Status.REVOKED;case SUPERSEDED -> VoteProcessingOutcome.Status.SUPERSEDED;case INVALIDATED -> VoteProcessingOutcome.Status.INVALIDATED; };
    }
    static void duplicateReview(Audit a,UUID vote,UUID operator,String reason,Review action,long revision) {
        if(!a.voteId().equals(vote)||!a.actor().equals(operator.toString())||!a.reason().equals(reason)||!a.kind().equals(action.name())||!Objects.equals(a.evidence().get("expected_revision"),revision))
            throw new VoteRejectedException(VoteRejectedException.Code.ID_CONFLICT);
    }
}
